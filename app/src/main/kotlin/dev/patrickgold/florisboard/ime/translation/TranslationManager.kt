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

package dev.patrickgold.florisboard.ime.translation

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Provides on-device text translation between any two of the supported languages, backed by ML Kit's
 * translation models. Each language is a separately downloadable pack; once the packs for a language pair are
 * on the device, translating between them works fully offline.
 *
 * Note that the model packs themselves are downloaded from Google's servers, and that ML Kit is a proprietary
 * (though freely usable) library - translation is therefore strictly opt-in and does nothing until the user
 * downloads the packs they want.
 */
class TranslationManager(context: Context) {
    companion object {
        /** Value of the source language preference meaning "whatever the keyboard subtype currently is". */
        const val SOURCE_LANGUAGE_SUBTYPE = "@subtype"
    }

    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val modelManager by lazy { RemoteModelManager.getInstance() }

    private val _downloadedLanguages = MutableStateFlow(emptySet<String>())
    /** The set of language codes whose translation pack is currently downloaded on this device. */
    val downloadedLanguages: StateFlow<Set<String>> = _downloadedLanguages.asStateFlow()

    private val _busyLanguages = MutableStateFlow(emptySet<String>())
    /** The set of language codes whose pack is currently being downloaded or deleted. */
    val busyLanguages: StateFlow<Set<String>> = _busyLanguages.asStateFlow()

    /** All language codes ML Kit can translate between, sorted by their display name in the user's locale. */
    val supportedLanguages: List<String> by lazy {
        TranslateLanguage.getAllLanguages().sortedBy { displayNameFor(it) }
    }

    init {
        refreshDownloadedLanguages()
    }

    /** Returns a human readable name for [languageCode], e.g. "ar" -> "العربية (Arabic)". */
    fun displayNameFor(languageCode: String): String {
        val locale = Locale.forLanguageTag(languageCode)
        val nativeName = locale.getDisplayName(locale)
        val localName = locale.getDisplayName(Locale.getDefault())
        return when {
            nativeName.isBlank() -> languageCode
            nativeName.equals(localName, ignoreCase = true) -> nativeName
            else -> "$nativeName ($localName)"
        }
    }

    fun refreshDownloadedLanguages() {
        scope.launch {
            runCatching {
                modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            }.onSuccess { models ->
                _downloadedLanguages.value = models.map { it.language }.toSet()
            }.onFailure { error ->
                flogDebug { "Failed to list downloaded translation models: $error" }
            }
        }
    }

    /** Downloads the translation pack for [languageCode]. Returns true if the pack is available afterwards. */
    suspend fun downloadLanguage(languageCode: String): Result<Unit> {
        val model = TranslateRemoteModel.Builder(languageCode).build()
        val conditions = DownloadConditions.Builder().apply {
            if (prefs.translation.downloadOverWifiOnly.get()) {
                requireWifi()
            }
        }.build()
        markBusy(languageCode, true)
        return runCatching {
            modelManager.download(model, conditions).await()
            Unit
        }.also {
            markBusy(languageCode, false)
            refreshDownloadedLanguages()
        }
    }

    /** Deletes the downloaded translation pack for [languageCode] to free up storage. */
    suspend fun deleteLanguage(languageCode: String): Result<Unit> {
        val model = TranslateRemoteModel.Builder(languageCode).build()
        markBusy(languageCode, true)
        return runCatching {
            modelManager.deleteDownloadedModel(model).await()
            Unit
        }.also {
            markBusy(languageCode, false)
            refreshDownloadedLanguages()
        }
    }

    private fun markBusy(languageCode: String, busy: Boolean) {
        _busyLanguages.update { current ->
            if (busy) current + languageCode else current - languageCode
        }
    }

    /**
     * Translates [text] from [sourceLanguage] to [targetLanguage].
     *
     * @param downloadIfNeeded If true, the required packs are fetched when they are missing, which needs a
     *  network connection. If false, the translation fails instead of silently using data.
     */
    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        downloadIfNeeded: Boolean = true,
    ): Result<String> {
        if (text.isBlank()) return Result.success(text)
        if (sourceLanguage == targetLanguage) return Result.success(text)
        val source = TranslateLanguage.fromLanguageTag(sourceLanguage)
            ?: return Result.failure(UnsupportedLanguageException(sourceLanguage))
        val target = TranslateLanguage.fromLanguageTag(targetLanguage)
            ?: return Result.failure(UnsupportedLanguageException(targetLanguage))
        val downloaded = _downloadedLanguages.value
        if (!downloadIfNeeded && (source !in downloaded || target !in downloaded)) {
            return Result.failure(MissingLanguagePackException(listOf(source, target).filter { it !in downloaded }))
        }
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
        )
        return try {
            val conditions = DownloadConditions.Builder().apply {
                if (prefs.translation.downloadOverWifiOnly.get()) {
                    requireWifi()
                }
            }.build()
            translator.downloadModelIfNeeded(conditions).await()
            val result = translator.translate(text).await()
            refreshDownloadedLanguages()
            Result.success(result)
        } catch (e: Exception) {
            flogDebug { "Translation from '$source' to '$target' failed: $e" }
            Result.failure(e)
        } finally {
            translator.close()
        }
    }

    class UnsupportedLanguageException(val languageCode: String) :
        Exception("Language '$languageCode' is not supported for translation")

    class MissingLanguagePackException(val languageCodes: List<String>) :
        Exception("Missing translation packs for: ${languageCodes.joinToString()}")
}

/**
 * Awaits the result of this [Task] without pulling in the kotlinx-coroutines-play-services artifact.
 */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        continuation.resume(result)
    }
    addOnFailureListener { error ->
        continuation.resumeWithException(error)
    }
    addOnCanceledListener {
        continuation.cancel()
    }
}
