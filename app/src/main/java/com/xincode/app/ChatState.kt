package com.xincode.app

import android.util.Log
import com.xincode.data.AppDatabase
import com.xincode.data.MessageEntity
import com.xincode.provider.ApiError
import com.xincode.provider.OpenAiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

class ChatState(
    private val database: AppDatabase,
    private val openAiClient: OpenAiClient
) : ChatStateLike {
    companion object {
        private const val TAG = "ChatState"
    }

    private val messageDao = database.messageDao()

    // ---- observable state ----
    override val messages = mutableStateListOf<ChatState.MessageUi>()
    override val input = mutableStateOf("")
    override val isStreaming = mutableStateOf(false)
    override val statusLine = mutableStateOf("")

    private var activeJob: Job? = null
    private var scope: CoroutineScope? = null

    /** Bind coroutine scope (called from Composable). */
    fun init(scope: CoroutineScope) {
        this.scope = scope
    }

    override fun stop() {
        activeJob?.cancel()
        activeJob = null
    }

    data class MessageUi(
        val id: Long,
        val role: String,
        val content: String,
        val timestamp: Long,
        val reasoning: String = "",
        val turnId: Long = 0,
        val confirmState: String? = null,  // null|"pending"|"allowed_once"|"always_allowed"|"denied"
        val confirmCommand: String? = null,
        val confirmRisk: String? = null,
        val contentBlock: MessageContent? = null
    )

    // ---- load history on start ----
    override suspend fun loadHistory() {
        val entities = withContext(Dispatchers.IO) { messageDao.getAll() }
        messages.clear()
        entities.forEach { msg ->
            messages.add(MessageUi(msg.id, msg.role, msg.content, msg.timestamp))
        }
        Log.d(TAG, "Loaded ${messages.size} messages from history")
    }

    // ---- send ----
    override fun send() {
        val s = scope ?: return
        val text = input.value.trim()
        if (text.isEmpty() || isStreaming.value) return

        input.value = ""

        s.launch {
            activeJob = coroutineContext[Job]
            isStreaming.value = true
            statusLine.value = ""
            Log.d(TAG, "===== stream start, isStreaming=true, scope=${s}, scope.isActive=${s.coroutineContext[Job]?.isActive} =====")
            var errorOccurred = false
            var asstId = 0L
            var asstIdx = -1
            val tokenChannel = Channel<String>(Channel.UNLIMITED)
            val firstTokenReceived = java.util.concurrent.atomic.AtomicBoolean(false)

            try {
                // 1. Insert user message
                val userMsg = MessageEntity(role = "user", content = text)
                val userId = withContext(Dispatchers.IO) { messageDao.insert(userMsg) }
                messages.add(MessageUi(userId, "user", text, userMsg.timestamp))

                // 2. Insert empty assistant placeholder
                val asstMsg = MessageEntity(role = "assistant", content = "")
                asstId = withContext(Dispatchers.IO) { messageDao.insert(asstMsg) }
                messages.add(MessageUi(asstId, "assistant", "", asstMsg.timestamp))
                asstIdx = messages.size - 1

                // 3. Token channel + 16ms frame-aligned consumer
                val consumer = launch(Dispatchers.Main) {
                    Log.d(TAG, "===== consumer started =====")
                    val buffer = StringBuilder()
                    var lastDbUpdate = 0L
                    while (isActive) {
                        // Exit immediately if channel is closed — don't wait for empty
                        if (tokenChannel.isClosedForReceive) {
                            Log.d(TAG, "===== consumer: channel closed, breaking loop =====")
                            break
                        }
                        delay(16)
                        var drained = 0
                        while (true) {
                            val result = tokenChannel.tryReceive()
                            if (result.isSuccess) {
                                buffer.append(result.getOrThrow())
                                drained++
                                firstTokenReceived.set(true)
                            } else break
                        }
                        if (drained > 0) {
                            Log.d(TAG, "===== consumer drained $drained tokens =====")
                            val currentContent = buffer.toString()
                            if (asstIdx >= 0) messages[asstIdx] = messages[asstIdx].copy(content = currentContent)
                            val now = System.currentTimeMillis()
                            if (now - lastDbUpdate > 500) {
                                launch(Dispatchers.IO) {
                                    messageDao.updateContent(asstId, currentContent)
                                }
                                lastDbUpdate = now
                            }
                        }
                    }
                    // Final drain — consume anything left in the channel
                    while (true) {
                        val result = tokenChannel.tryReceive()
                        if (result.isSuccess) buffer.append(result.getOrThrow())
                        else break
                    }
                    val finalContent = buffer.toString()
                    if (finalContent.isNotEmpty() && asstIdx >= 0) {
                        messages[asstIdx] = messages[asstIdx].copy(content = finalContent)
                    }
                    Log.d(TAG, "===== consumer finished =====")
                }

                // 4. 5s watchdog
                val watchdog = launch {
                    delay(5_000)
                    if (!firstTokenReceived.get() && isActive) {
                        Log.w(TAG, "Watchdog: no token in 5s, aborting")
                        errorOccurred = true
                        tokenChannel.close()
                        if (asstIdx >= 0) messages[asstIdx] = messages[asstIdx].copy(content = "连接超时 — 未在 5 秒内收到响应")
                        withContext(Dispatchers.IO) {
                            messageDao.updateContent(asstId, "连接超时 — 未在 5 秒内收到响应")
                        }
                    }
                }

                // 5. Call streaming API — wrapped in a Job for onCompletion hook
                val streamJob = launch(Dispatchers.IO) {
                    openAiClient.chatStream(
                        userMessage = text,
                        systemPrompt = "You are a helpful assistant. Respond in Chinese.",
                        onToken = { token -> tokenChannel.trySend(token) },
                        onReasoning = { firstTokenReceived.set(true) },  // reset watchdog on reasoning
                        onComplete = {
                            tokenChannel.close()
                            watchdog.cancel()
                        },
                        onError = { apiError ->
                            Log.e(TAG, "Stream error: ${apiError.message}", apiError)
                            errorOccurred = true
                            tokenChannel.close()
                            watchdog.cancel()
                            if (asstIdx >= 0) messages[asstIdx] = messages[asstIdx].copy(content = "✗ ${apiError.message}")
                            withContext(Dispatchers.IO) {
                                messageDao.updateContent(asstId, "✗ ${apiError.message}")
                            }
                        }
                    )
                }
                streamJob.invokeOnCompletion { cause ->
                    Log.d(TAG, "===== stream onCompletion, cause=$cause, causeClass=${cause?.javaClass?.name}, isCancellation=${cause is kotlinx.coroutines.CancellationException} =====")
                    // Ensure channel is closed (might already be, but double-safe)
                    tokenChannel.close()
                    watchdog.cancel()
                }

                // 6. Wait for stream to finish
                streamJob.join()
                // 7. Wait for consumer to finish draining
                consumer.join()
                watchdog.cancel()

                // 8. Final DB save (only if no error)
                if (!errorOccurred && asstIdx >= 0) {
                    val finalContent = messages[asstIdx].content
                    withContext(Dispatchers.IO) {
                        messageDao.updateContent(asstId, finalContent)
                    }
                    Log.i(TAG, "Stream complete: ${finalContent.take(80)}...")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "===== stream cancelled, cause=${e.message}, coroutine=${e.cause} =====")
                tokenChannel.close()
            } catch (e: Exception) {
                Log.e(TAG, "===== stream exception: ${e.message} =====", e)
                tokenChannel.close()
                statusLine.value = "✗ ${e.message}"
            } finally {
                isStreaming.value = false
                activeJob = null
                Log.d(TAG, "===== stream end, isStreaming=false =====")
            }
        }
    }
}