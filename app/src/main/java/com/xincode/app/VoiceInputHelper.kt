package com.xincode.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wraps Android SpeechRecognizer for voice-to-text input.
 * State machine: Idle → Starting → Listening → Processing → Result/Error → Idle
 */
class VoiceInputHelper(private val context: Context) {

    companion object {
        private const val TAG = "VoiceInput"
    }

    enum class State { IDLE, STARTING, LISTENING, PROCESSING, RESULT, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText

    private val _finalText = MutableStateFlow("")
    val finalText: StateFlow<String> = _finalText

    private val _errorMsg = MutableStateFlow("")
    val errorMsg: StateFlow<String> = _errorMsg

    private var recognizer: SpeechRecognizer? = null
    private var recognitionGeneration = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available")
            _state.value = State.ERROR
            _errorMsg.value = "设备不支持语音识别"
            return
        }

        destroyRecognizer(cancel = true)
        val generation = ++recognitionGeneration
        _state.value = State.STARTING
        _partialText.value = ""
        _finalText.value = ""
        _errorMsg.value = ""

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (generation != recognitionGeneration || _state.value != State.STARTING) return
                    Log.d(TAG, "Ready for speech")
                    _state.value = State.LISTENING
                    _partialText.value = ""
                    _finalText.value = ""
                    _errorMsg.value = ""
                }

                override fun onBeginningOfSpeech() {
                    if (generation != recognitionGeneration) return
                    Log.d(TAG, "Speech begun")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (generation != recognitionGeneration) return
                }
                override fun onBufferReceived(buffer: ByteArray?) {
                    if (generation != recognitionGeneration) return
                }

                override fun onEndOfSpeech() {
                    if (generation != recognitionGeneration) return
                    Log.d(TAG, "Speech ended")
                    _state.value = State.PROCESSING
                }

                override fun onError(error: Int) {
                    if (generation != recognitionGeneration) return
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别引擎忙"
                        SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音输入"
                        else -> "未知错误 ($error)"
                    }
                    Log.w(TAG, "Recognition error: $msg")
                    destroyRecognizer(cancel = false)
                    _state.value = State.ERROR
                    _errorMsg.value = msg
                }

                override fun onResults(results: Bundle?) {
                    if (generation != recognitionGeneration) return
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    Log.i(TAG, "Final result: $text")
                    destroyRecognizer(cancel = false)
                    _finalText.value = text
                    _partialText.value = ""
                    _state.value = if (text.isNotEmpty()) State.RESULT else State.ERROR
                    if (text.isEmpty()) _errorMsg.value = "未识别到语音"
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (generation != recognitionGeneration) return
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotEmpty()) {
                        _partialText.value = text
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    if (generation != recognitionGeneration) return
                }
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            recognizer?.startListening(intent)
            mainHandler.postDelayed({
                if (generation == recognitionGeneration && _state.value == State.STARTING) {
                    destroyRecognizer(cancel = true)
                    _state.value = State.ERROR
                    _errorMsg.value = "语音识别服务启动超时"
                }
            }, 8_000L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            destroyRecognizer(cancel = true)
            _state.value = State.ERROR
            _errorMsg.value = "启动语音识别失败: ${e.message}"
        }
    }

    /** Finish the current utterance and let SpeechRecognizer return its best result. */
    fun finishListening() {
        if (_state.value != State.STARTING && _state.value != State.LISTENING) return
        _state.value = State.PROCESSING
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to finish listening", e)
            destroyRecognizer(cancel = true)
            _state.value = State.ERROR
            _errorMsg.value = "停止录音失败: ${e.message}"
        }
    }

    private fun destroyRecognizer(cancel: Boolean) {
        try {
            if (cancel) recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        } finally {
            recognizer = null
        }
    }

    fun reset() {
        recognitionGeneration++
        destroyRecognizer(cancel = true)
        _state.value = State.IDLE
        _partialText.value = ""
        _finalText.value = ""
        _errorMsg.value = ""
    }
}
