package com.xincode.app

import android.content.Context
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 语音消息录制:MediaRecorder → AAC/m4a 落盘到应用私有目录,
 * 作为音频附件随消息发给 AI。支持音频输入的模型直接听;未配语音能力的
 * 模型可以用 transcribe_audio 工具转写——都不需要先把转写文字塞进输入框。
 */
object VoiceMessageRecorder {

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    private val _durationSec = MutableStateFlow(0)
    val durationSec: StateFlow<Int> = _durationSec

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var ticker: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 开始录音;失败返回 false(状态复位,不留半开资源)。 */
    @Synchronized
    fun start(context: Context): Boolean {
        if (_recording.value) return true
        return try {
            val dir = File(context.filesDir, "voice").apply { mkdirs() }
            val f = File(dir, "voice_${System.currentTimeMillis()}.m4a")
            val mr = if (android.os.Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44100)
            mr.setAudioEncodingBitRate(96_000)
            mr.setOutputFile(f.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            outFile = f
            _durationSec.value = 0
            _recording.value = true
            ticker = scope.launch {
                while (_recording.value) {
                    delay(1000)
                    if (_recording.value) _durationSec.value += 1
                }
            }
            true
        } catch (e: Exception) {
            runCatching { recorder?.release() }
            recorder = null
            outFile?.delete()
            outFile = null
            _recording.value = false
            false
        }
    }

    /** 停止录音并返回音频附件;失败时清理并返回 null。 */
    @Synchronized
    fun stop(): Attachment? {
        if (!_recording.value) return null
        val f = outFile
        val mr = recorder
        _recording.value = false
        ticker?.cancel()
        return try {
            mr?.stop()
            val size = f?.length() ?: 0L
            if (f == null || size <= 0L) null
            else Attachment(
                fileName = f.name,
                absolutePath = f.absolutePath,
                sizeBytes = size,
                mimeType = "audio/mp4",
                content = ""
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mr?.release() }
            recorder = null
            outFile = null
            _durationSec.value = 0
        }
    }
}
