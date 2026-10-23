package com.roalyr.customkeyboardengine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Speech-to-text for the keyboard, built on [SpeechRecognizer].
 *
 * States: idle, held (stops when the key is released) and locked (keeps listening until toggled off).
 * The recognizer ends a session after a pause, so it restarts while the user still wants to listen.
 * Must be used from the main thread.
 */
class VoiceInput(
    private val context: Context,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onSessionEndedWithoutResult: () -> Unit,
    private val onActiveChanged: (Boolean) -> Unit,
) {
    private enum class State { IDLE, HELD, LOCKED }

    private var state = State.IDLE
    private var recognizer: SpeechRecognizer? = null

    val isActive: Boolean get() = state != State.IDLE

    /** Voice key pressed: stop a locked session, or start a held one. */
    fun onKeyDown() {
        if (state == State.LOCKED) stop() else start(State.HELD)
    }

    /** Voice key swiped up while held. */
    fun lock() {
        if (state == State.HELD) state = State.LOCKED
    }

    /** Voice key released: a held session stops, a locked one continues. */
    fun onKeyUp() {
        if (state == State.HELD) stop()
    }

    /** Swipe or long-press action: toggles a locked session. */
    fun toggleLocked() {
        if (state == State.IDLE) start(State.LOCKED) else stop()
    }

    fun stop() {
        if (state == State.IDLE) return
        state = State.IDLE
        // Pending speech still arrives through onResults after stopListening.
        recognizer?.stopListening()
        onActiveChanged(false)
    }

    fun destroy() {
        state = State.IDLE
        recognizer?.destroy()
        recognizer = null
    }

    private fun start(newState: State) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, R.string.voice_permission_needed, Toast.LENGTH_SHORT).show()
            val intent = Intent(context, ActivityPermissionRequest::class.java).apply {
                putExtra(Constants.PermissionTypes.EXTRA_TYPE, Constants.PermissionTypes.MICROPHONE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, R.string.voice_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        state = newState
        onActiveChanged(true)
        listen()
    }

    private fun listen() {
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        rec.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let(onPartialText)
        }

        override fun onResults(results: Bundle?) {
            firstResult(results)?.let(onFinalText)
            if (state != State.IDLE) listen()
        }

        override fun onError(error: Int) {
            onSessionEndedWithoutResult()
            val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            if (state != State.IDLE && recoverable) {
                listen()
            } else if (state != State.IDLE) {
                Log.w(TAG, "Speech recognition error $error")
                Toast.makeText(context, context.getString(R.string.voice_error, error), Toast.LENGTH_SHORT).show()
                stop()
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "VoiceInput"
    }
}
