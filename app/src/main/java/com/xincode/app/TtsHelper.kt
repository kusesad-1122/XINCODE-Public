package com.xincode.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Wraps Android TextToSpeech for reading AI responses aloud.
 * State: Idle → Speaking → Idle
 */
class TtsHelper(private val context: Context) {

    companion object {
        private const val TAG = "TtsHelper"
    }

    enum class State { IDLE, SPEAKING }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private var tts: TextToSpeech? = null
    private var ready = false

    fun initialize() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ready) {
                    // Fallback to English
                    tts?.setLanguage(Locale.US)
                    ready = true
                }
                Log.i(TAG, "TTS initialized, ready=$ready")

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _state.value = State.SPEAKING
                    }
                    override fun onDone(utteranceId: String?) {
                        _state.value = State.IDLE
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _state.value = State.IDLE
                        Log.w(TAG, "TTS error for $utteranceId")
                    }
                })
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    fun toggleEnabled() {
        _enabled.value = !_enabled.value
        if (!_enabled.value) stop()
        Log.i(TAG, "TTS enabled=${_enabled.value}")
    }

    fun speak(text: String) {
        if (!_enabled.value || !ready || text.isBlank()) return

        // Strip markdown and code blocks for cleaner speech
        val cleanText = text
            .replace(Regex("```[\\s\\S]*?```"), "代码块已省略")
            .replace(Regex("`[^`]+`"), "") // inline code
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1") // bold
            .replace(Regex("\\*([^*]+)\\*"), "$1") // italic
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "") // headers
            .replace(Regex("^[-*]\\s+", RegexOption.MULTILINE), "") // list items
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1") // links
            .replace(Regex("\n{2,}"), "\n")
            .trim()

        if (cleanText.isBlank()) return

        Log.i(TAG, "Speaking ${cleanText.length} chars")
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "xincode_tts_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
        _state.value = State.IDLE
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}