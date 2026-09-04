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
    private val appContext = context.applicationContext

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var onTextRecognized: ((String) -> Unit)? = null

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
        _partialText.value = ""
        _soundLevel.value = 0f
        _state.value = VoiceInputState.Listening

        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also { this.recognizer = it }
        recognizer.setRecognitionListener(Listener())
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
        runCatching { recognizer.startListening(intent) }.onFailure { error ->
            flogDebug { "Failed to start speech recognition: $error" }
            finish(VoiceInputState.Error(VoiceInputError.FAILED))
        }
    }

    /** Stops recording but still waits for the final recognition result of what was already said. */
    fun stop() = onMainThread {
        if (isActive) {
            _state.value = VoiceInputState.Processing
            runCatching { recognizer?.stopListening() }.onFailure { finish(VoiceInputState.Inactive) }
        }
    }

    /** Aborts dictation entirely, discarding anything that has not been committed yet. */
    fun cancel() = onMainThread {
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
        runCatching { recognizer?.destroy() }
        recognizer = null
        onTextRecognized = null
        _partialText.value = ""
        _soundLevel.value = 0f
        _state.value = newState
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = VoiceInputState.Listening
        }

        override fun onBeginningOfSpeech() {
            _state.value = VoiceInputState.Listening
        }

        override fun onRmsChanged(rmsdB: Float) {
            // The documented range is -2..10 dB, map it into 0..1 for the UI.
            _soundLevel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (isActive) {
                _state.value = VoiceInputState.Processing
            }
        }

        override fun onError(error: Int) {
            val mapped = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceInputError.NO_PERMISSION
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceInputError.NETWORK
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceInputError.NO_SPEECH
                else -> VoiceInputError.FAILED
            }
            flogDebug { "Speech recognition error $error -> $mapped" }
            finish(VoiceInputState.Error(mapped))
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                onTextRecognized?.invoke(text)
            }
            finish(VoiceInputState.Inactive)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text != null) {
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
