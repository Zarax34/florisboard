/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives on-device/system speech recognition from within the keyboard itself, so that dictation happens
 * while the FlorisBoard UI stays on screen instead of handing the input session over to a separate voice IME.
 *
 * All [SpeechRecognizer] interaction must happen on the main thread, which is why the public entry points
 * hop onto the main looper themselves rather than relying on their callers to do so.
 */
class VoiceInputManager(context: Context) {
    companion object {
        /**
         * How long to wait before handing the recognizer a fresh request. Restarting it in the same frame
         * makes some implementations report ERROR_RECOGNIZER_BUSY instead of listening.
         */
        private const val RESTART_DELAY_MS = 120L

        /** Slightly longer backoff for the case where the recognizer explicitly said it was still busy. */
        private const val BUSY_RESTART_DELAY_MS = 400L

        /**
         * Give up after this much uninterrupted silence. Dictation is meant to stay open until the user ends
         * it, but an open microphone that has heard nothing for minutes is a battery drain, not a feature.
         */
        private const val MAX_SILENCE_MS = 120_000L

        /**
         * A microphone level above this counts as "something is being said", which keeps the silence timer
         * from running out while the user is talking but the recognizer has not produced a result yet.
         */
        private const val SPEECH_RMS_THRESHOLD = 0.25f
    }

    private val appContext = context.applicationContext

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var onTextRecognized: ((String) -> Unit)? = null

    /** The language of the running session, kept so a restart can reuse it. */
    private var activeLanguageTag: String? = null

    /** Set once the user asks to stop, so the pending final result ends the session instead of restarting. */
    private var isStopping = false

    /** When speech was last actually heard, used to give up after a long stretch of pure silence. */
    private var lastSpeechAtMs = 0L

    /** The queued restart, so stopping can cancel it instead of letting it revive the session. */
    private var pendingRestart: Runnable? = null

    private val _state = MutableStateFlow<VoiceInputState>(VoiceInputState.Inactive)
    /** The current dictation state, drives the voice input bar UI. */
    val state: StateFlow<VoiceInputState> = _state.asStateFlow()

    private val _partialText = MutableStateFlow("")
    /** The text recognized so far in the current utterance, shown live while the user is speaking. */
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _soundLevel = MutableStateFlow(0f)
    /** A normalized 0..1 microphone level, used to animate the listening indicator. */
    val soundLevel: StateFlow<Float> = _soundLevel.asStateFlow()

    /** Whether this device has any speech recognition service that we could use at all. */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    /** Whether the user has already granted the microphone permission needed for dictation. */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Whether a dictation session is currently running. Deliberately false in the error state, so that
     * tapping the microphone again after a failure starts a fresh attempt instead of trying to stop a
     * recognizer that is already gone.
     */
    val isActive: Boolean
        get() = _state.value.let { it == VoiceInputState.Listening || it == VoiceInputState.Processing }

    /**
     * Starts listening and reports every recognized chunk of text through [onText]. Language is a BCP-47 tag
     * such as `ar` or `en-US`, normally the active keyboard subtype's locale.
     */
    fun start(languageTag: String, onText: (String) -> Unit) = onMainThread {
        startInternal(languageTag, onText)
    }

    private fun startInternal(languageTag: String, onText: (String) -> Unit) {
        if (isActive) return
        if (!isAvailable()) {
            _state.value = VoiceInputState.Error(VoiceInputError.NOT_AVAILABLE)
            return
        }
        if (!hasPermission()) {
            _state.value = VoiceInputState.Error(VoiceInputError.NO_PERMISSION)
            return
        }
        onTextRecognized = onText
        activeLanguageTag = languageTag
        isStopping = false
        lastSpeechAtMs = SystemClock.elapsedRealtime()
        _partialText.value = ""
        _soundLevel.value = 0f
        _state.value = VoiceInputState.Listening

        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also { this.recognizer = it }
        recognizer.setRecognitionListener(Listener())
        listen()
    }

    /** Builds the recognition request for the running session's language. */
    private fun recognitionIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeLanguageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // Asks the recognizer to tolerate a longer pause before deciding the sentence is over. Not every
            // implementation honours these, which is exactly why the session is also restarted below.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
        }
    }

    private fun listen() {
        val recognizer = this.recognizer ?: return
        runCatching { recognizer.startListening(recognitionIntent()) }.onFailure { error ->
            flogDebug { "Failed to start speech recognition: $error" }
            finish(VoiceInputState.Error(VoiceInputError.FAILED))
        }
    }

    /**
     * Continues the session after the recognizer decided an utterance was over.
     *
     * Android's [SpeechRecognizer] has no continuous mode: it stops at the first pause in speech and reports
     * a final result. Dictation that ends whenever the user draws breath is useless, so unless the user has
     * asked to stop, a fresh request is queued and the session simply carries on.
     */
    private fun restartListening(delayMillis: Long = RESTART_DELAY_MS) {
        if (isStopping || recognizer == null) return
        if (SystemClock.elapsedRealtime() - lastSpeechAtMs > MAX_SILENCE_MS) {
            finish(VoiceInputState.Inactive)
            return
        }
        cancelPendingRestart()
        _partialText.value = ""
        _state.value = VoiceInputState.Listening
        val restart = Runnable {
            pendingRestart = null
            if (!isStopping && recognizer != null) {
                listen()
            }
        }
        pendingRestart = restart
        mainHandler.postDelayed(restart, delayMillis)
    }

    private fun cancelPendingRestart() {
        pendingRestart?.let { mainHandler.removeCallbacks(it) }
        pendingRestart = null
    }

    /** Stops recording but still waits for the final recognition result of what was already said. */
    fun stop() = onMainThread {
        if (isActive) {
            isStopping = true
            // If a restart was queued we are between utterances and nothing is being recorded, so there is no
            // final result coming and the session can end right away.
            if (pendingRestart != null) {
                cancelPendingRestart()
                finish(VoiceInputState.Inactive)
            } else {
                _state.value = VoiceInputState.Processing
                runCatching { recognizer?.stopListening() }.onFailure { finish(VoiceInputState.Inactive) }
            }
        }
    }

    /** Aborts dictation entirely, discarding anything that has not been committed yet. */
    fun cancel() = onMainThread {
        isStopping = true
        cancelPendingRestart()
        runCatching { recognizer?.cancel() }
        finish(VoiceInputState.Inactive)
    }

    private inline fun onMainThread(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    private fun finish(newState: VoiceInputState) {
        cancelPendingRestart()
        runCatching { recognizer?.destroy() }
        recognizer = null
        onTextRecognized = null
        activeLanguageTag = null
        isStopping = false
        _partialText.value = ""
        _soundLevel.value = 0f
        _state.value = newState
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = VoiceInputState.Listening
        }

        override fun onBeginningOfSpeech() {
            lastSpeechAtMs = SystemClock.elapsedRealtime()
            _state.value = VoiceInputState.Listening
        }

        override fun onRmsChanged(rmsdB: Float) {
            // The documented range is -2..10 dB, map it into 0..1 for the UI.
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _soundLevel.value = level
            if (level >= SPEECH_RMS_THRESHOLD) {
                lastSpeechAtMs = SystemClock.elapsedRealtime()
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            // Only a pause, not the end of dictation - but transcribing the chunk takes a moment.
            if (isActive) {
                _state.value = VoiceInputState.Processing
            }
        }

        override fun onError(error: Int) {
            flogDebug { "Speech recognition error $error (isStopping=$isStopping)" }
            when (error) {
                // Silence, not a failure. The user is thinking, or the recognizer chopped the sentence up:
                // pick the microphone back up and keep going unless they have asked to stop.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (isStopping) finish(VoiceInputState.Inactive) else restartListening()
                }
                // The previous request has not fully released the microphone yet, back off and retry.
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    if (isStopping) finish(VoiceInputState.Inactive) else restartListening(BUSY_RESTART_DELAY_MS)
                }
                else -> {
                    val mapped = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceInputError.NO_PERMISSION
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceInputError.NETWORK
                        else -> VoiceInputError.FAILED
                    }
                    finish(VoiceInputState.Error(mapped))
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                lastSpeechAtMs = SystemClock.elapsedRealtime()
                onTextRecognized?.invoke(text)
            }
            // A result means the recognizer considers this utterance finished, not that dictation is over.
            if (isStopping) finish(VoiceInputState.Inactive) else restartListening()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text != null) {
                if (text.isNotBlank()) {
                    lastSpeechAtMs = SystemClock.elapsedRealtime()
                }
                _partialText.value = text
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

sealed interface VoiceInputState {
    /** Not dictating - the voice bar is hidden. */
    data object Inactive : VoiceInputState

    /** The microphone is open and the user is expected to speak. */
    data object Listening : VoiceInputState

    /** Recording stopped, waiting for the recognizer to return its final result. */
    data object Processing : VoiceInputState

    /** Dictation ended because of [reason]; shown in the bar until dismissed. */
    data class Error(val reason: VoiceInputError) : VoiceInputState
}

enum class VoiceInputError {
    /** No speech recognition service is installed on this device. */
    NOT_AVAILABLE,

    /** The microphone permission has not been granted (yet). */
    NO_PERMISSION,

    /** The recognizer needed the network and could not reach it. */
    NETWORK,

    /** Nothing intelligible was said. */
    NO_SPEECH,

    /** Any other recognizer failure. */
    FAILED,
}
