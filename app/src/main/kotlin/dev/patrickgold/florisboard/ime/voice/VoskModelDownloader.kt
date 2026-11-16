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

import androidx.annotation.StringRes
import dev.patrickgold.florisboard.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/** An official Vosk model offered for download in the settings. */
data class VoskCatalogModel(
    val language: String,
    val name: String,
    val sizeMb: Int,
    @StringRes val note: Int? = null,
) {
    val url: String get() = "https://alphacephei.com/vosk/models/$name.zip"
}

val VoskModelCatalog = listOf(
    VoskCatalogModel("ar", "vosk-model-ar-mgb2-0.4", 333, R.string.settings__voice_models__note_ar_mgb2),
    VoskCatalogModel("ar", "vosk-model-small-ar-tn-0.1-linto", 166, R.string.settings__voice_models__note_ar_small),
    VoskCatalogModel("en", "vosk-model-small-en-us-0.15", 41),
    VoskCatalogModel("fr", "vosk-model-small-fr-0.22", 42),
    VoskCatalogModel("es", "vosk-model-small-es-0.42", 40),
    VoskCatalogModel("de", "vosk-model-small-de-0.15", 46),
    VoskCatalogModel("ru", "vosk-model-small-ru-0.22", 46),
    VoskCatalogModel("tr", "vosk-model-small-tr-0.3", 37),
    VoskCatalogModel("fa", "vosk-model-small-fa-0.42", 53),
)

/**
 * Downloads catalog models straight into [VoskModels.import] (the zip is unpacked while it streams in, so no extra
 * storage is needed and a broken download leaves nothing behind). Downloads keep running when the settings screen
 * is closed.
 */
object VoskModelDownloader {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Running downloads: model name to progress 0..1. */
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    fun start(models: VoskModels, model: VoskCatalogModel, onDone: (Result<Unit>) -> Unit) {
        // One download per language at a time, they unpack into the same folder
        if (VoskModelCatalog.any { it.language == model.language && jobs.containsKey(it.name) }) return
        _progress.update { it + (model.name to 0f) }
        jobs[model.name] = scope.launch {
            val result = runCatching { download(models, model) }
            jobs.remove(model.name)
            _progress.update { it - model.name }
            if (result.exceptionOrNull() !is CancellationException) {
                withContext(Dispatchers.Main) { onDone(result) }
            }
        }
    }

    fun cancel(model: VoskCatalogModel) {
        jobs[model.name]?.cancel()
    }

    private suspend fun download(models: VoskModels, model: VoskCatalogModel) {
        val connection = URL(model.url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} for ${model.url}")
            }
            val total = connection.contentLengthLong
            val context = currentCoroutineContext()
            var lastPercent = 0
            ProgressInputStream(connection.inputStream, context) { bytesRead ->
                val percent = if (total > 0) (bytesRead * 100 / total).toInt() else 0
                if (percent != lastPercent) {
                    lastPercent = percent
                    _progress.update { it + (model.name to percent / 100f) }
                }
            }.use { models.import(model.language, it) }
        } finally {
            connection.disconnect()
        }
    }

    /** Reports bytes read and aborts the read when the download was cancelled. */
    private class ProgressInputStream(
        input: InputStream,
        private val context: CoroutineContext,
        private val onRead: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var bytesRead = 0L

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it) }

        private fun advance(count: Int) {
            context.ensureActive()
            bytesRead += count
            onRead(bytesRead)
        }
    }
}
