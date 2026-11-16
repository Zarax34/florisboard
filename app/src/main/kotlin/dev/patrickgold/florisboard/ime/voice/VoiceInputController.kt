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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.android.showShortToastSync
import org.json.JSONException
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException

/**
 * Voice typing inside the keyboard. If the user imported a Vosk model for the current language (see [VoskModels]),
 * recognition runs fully offline inside the keyboard. Otherwise the on-device recognizer (Android 12+) is preferred,
 * which works without internet once the language's offline speech pack is installed, and as a last resort the system
 * recognizer is asked to prefer offline. The live transcription is shown in the Smartbar, the text is committed to
 * the editor.
 *
 * Must be called from the main thread.
 */
class VoiceInputController(context: Context) {
    private val appContext by context.appContext()
    private val editorInstance by context.editorInstance()
    private val nlpManager by context.nlpManager()
    private val subtypeManager by context.subtypeManager()

    companion object {
        private const val VOSK_SAMPLE_RATE = 16_000f
        /** A loaded model takes a few hundred MB, release it when voice typing is not used for a while. */
        private const val VOSK_MODEL_IDLE_RELEASE_MS = 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val voskModels by lazy { VoskModels(appContext.filesDir) }

    private var recognizer: SpeechRecognizer? = null
    private var voskService: SpeechService? = null
    private var voskRecognizer: Recognizer? = null
    private var voskModel: Pair<String, Model>? = null
    private var voskStartJob: Job? = null
    private var voskReleaseJob: Job? = null

    val isListening: Boolean get() = recognizer != null || voskService != null || voskStartJob?.isActive == true

    private val activeLanguage: String get() = subtypeManager.activeSubtype.primaryLocale.language

    private val isOnDeviceAvailable: Boolean
        get() = AndroidVersion.ATLEAST_API31_S && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    /** True if this device has any speech recognizer the keyboard can use. */
    fun isAvailable(): Boolean {
        return voskModels.modelDir(activeLanguage) != null || isOnDeviceAvailable ||
            SpeechRecognizer.isRecognitionAvailable(appContext)
    }

    /** Starts listening, or stops if already listening. */
    fun toggle() {
        if (isListening) {
            when {
                voskService != null -> voskService?.stop() // delivers the rest of the speech in onFinalResult
                recognizer != null -> recognizer?.stopListening()
                else -> cancel() // Vosk model still loading
            }
            return
        }
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            VoicePermissionActivity.launch(appContext)
            return
        }
        val voskModelDir = voskModels.modelDir(activeLanguage)
        if (voskModelDir != null) {
            startVosk(activeLanguage, voskModelDir)
        } else {
            start(onDevice = isOnDeviceAvailable)
        }
    }

    fun cancel() {
        recognizer?.destroy()
        recognizer = null
        voskStartJob?.cancel()
        voskStartJob = null
        voskService?.let {
            it.cancel()
            it.shutdown()
        }
        voskService = null
        voskRecognizer?.close()
        voskRecognizer = null
        scheduleVoskModelRelease()
        nlpManager.clearSuggestions()
    }

    private fun startVosk(language: String, modelDir: File) {
        cancel()
        voskReleaseJob?.cancel()
        appContext.showShortToastSync(R.string.voice_input__listening)
        voskStartJob = scope.launch {
            try {
                val rec = Recognizer(loadVoskModel(language, modelDir), VOSK_SAMPLE_RATE)
                voskRecognizer = rec
                voskService = SpeechService(rec, VOSK_SAMPLE_RATE).also { it.startListening(VoskListener()) }
            } catch (e: IOException) {
                flogError { "Vosk failed to start for '$language': $e" }
                appContext.showShortToastSync(R.string.voice_input__error)
                cancel()
            }
        }
    }

    private suspend fun loadVoskModel(language: String, modelDir: File): Model {
        voskModel?.let { (loadedLanguage, model) ->
            if (loadedLanguage == language) return model
            model.close()
            voskModel = null
        }
        val model = withContext(Dispatchers.IO) { Model(modelDir.absolutePath) }
        voskModel = language to model
        return model
    }

    private fun scheduleVoskModelRelease() {
        if (voskModel == null) return
        voskReleaseJob?.cancel()
        voskReleaseJob = scope.launch {
            delay(VOSK_MODEL_IDLE_RELEASE_MS)
            voskModel?.second?.close()
            voskModel = null
        }
    }

    private fun recognizerIntent(): Intent {
        val locale = subtypeManager.activeSubtype.primaryLocale
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.localeTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }

    private fun start(onDevice: Boolean) {
        cancel()
        val sr = if (onDevice && AndroidVersion.ATLEAST_API31_S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
        recognizer = sr
        sr.setRecognitionListener(Listener(onDevice))
        sr.startListening(recognizerIntent())
        appContext.showShortToastSync(R.string.voice_input__listening)
    }

    private fun commit(text: String) {
        if (text.isBlank()) return
        val before = editorInstance.activeContent.textBeforeSelection
        val separator = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        editorInstance.commitText(separator + DictationFormatter.format(text, before))
    }

    /** Vosk results are json: `{"partial": "..."}` while speaking, `{"text": "..."}` after a pause. */
    private fun voskText(json: String?, key: String): String {
        return try {
            JSONObject(json ?: return "").optString(key)
        } catch (e: JSONException) {
            ""
        }
    }

    private inner class VoskListener : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val partial = voskText(hypothesis, "partial").takeIf { it.isNotBlank() } ?: return
            nlpManager.suggestDirectly(listOf(WordSuggestionCandidate(partial, confidence = 1.0)))
        }

        // Dictation continues after each pause until the user taps the mic again
        override fun onResult(hypothesis: String?) {
            nlpManager.clearSuggestions()
            commit(voskText(hypothesis, "text"))
        }

        override fun onFinalResult(hypothesis: String?) {
            commit(voskText(hypothesis, "text"))
            cancel()
        }

        override fun onError(exception: Exception?) {
            flogError { "Vosk error: $exception" }
            appContext.showShortToastSync(R.string.voice_input__error)
            cancel()
        }

        override fun onTimeout() = cancel()
    }

    private fun Bundle?.firstResult(): String? {
        return this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }

    private inner class Listener(private val onDevice: Boolean) : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults.firstResult()?.takeIf { it.isNotBlank() } ?: return
            nlpManager.suggestDirectly(listOf(WordSuggestionCandidate(partial, confidence = 1.0)))
        }

        override fun onResults(results: Bundle?) {
            cancel()
            commit(results.firstResult().orEmpty())
        }

        override fun onError(error: Int) {
            val languageMissing = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            when {
                onDevice && languageMissing -> {
                    // Offline pack for this language is missing: ask the system to download it, and meanwhile
                    // use the regular recognizer (still preferring offline).
                    if (AndroidVersion.ATLEAST_API33_T) {
                        recognizer?.triggerModelDownload(recognizerIntent())
                        appContext.showShortToastSync(R.string.voice_input__downloading_offline_model)
                    }
                    start(onDevice = false)
                }
                error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    cancel()
                    appContext.showShortToastSync(R.string.voice_input__no_match)
                }
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    cancel()
                    VoicePermissionActivity.launch(appContext)
                }
                else -> {
                    cancel()
                    flogError { "Speech recognizer error $error (onDevice=$onDevice)" }
                    appContext.showShortToastSync(R.string.voice_input__error)
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
