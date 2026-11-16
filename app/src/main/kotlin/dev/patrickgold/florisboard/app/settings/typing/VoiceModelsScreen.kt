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

package dev.patrickgold.florisboard.app.settings.typing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.voice.VoskModelCatalog
import dev.patrickgold.florisboard.ime.voice.VoskModelDownloader
import dev.patrickgold.florisboard.ime.voice.VoskModels
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.jetpref.material.ui.JetPrefListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showLongToastSync
import org.florisboard.lib.compose.FlorisIconButton
import org.florisboard.lib.compose.stringRes
import java.io.IOException

/** Import and remove Vosk models, which make voice typing work fully offline. */
@Composable
fun VoiceModelsScreen() = FlorisScreen {
    title = stringRes(R.string.settings__voice_models__title)
    previewFieldVisible = false
    scrollable = false

    val context = LocalContext.current
    val subtypeManager by context.subtypeManager()
    val scope = rememberCoroutineScope()
    val voskModels = remember { VoskModels(context.filesDir) }

    var languages by remember { mutableStateOf(voskModels.installedLanguages()) }
    var isImporting by remember { mutableStateOf(false) }
    val downloads by VoskModelDownloader.progress.collectAsState()
    // A finished download changes the installed models
    LaunchedEffect(downloads.keys) { languages = voskModels.installedLanguages() }
    val appContext = context.applicationContext

    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val language = displayName(context, uri)?.let { VoskModels.languageFromFileName(it) }
            ?: subtypeManager.activeSubtype.primaryLocale.language
        isImporting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
                    input.use { voskModels.import(language, it) }
                }
            }
            isImporting = false
            languages = voskModels.installedLanguages()
            result.onSuccess {
                context.showLongToastSync(R.string.settings__voice_models__import_success, "language" to languageName(language))
            }.onFailure { error ->
                flogError { "Vosk model import failed: $error" }
                context.showLongToastSync(R.string.settings__voice_models__import_failed)
            }
        }
    }

    floatingActionButton {
        if (!isImporting) {
            ExtendedFloatingActionButton(
                onClick = { importModel.launch("application/zip") },
                icon = { Icon(imageVector = Icons.Default.Add, contentDescription = null) },
                text = { Text(text = stringRes(R.string.settings__voice_models__import)) },
            )
        }
    }

    content {
        LazyColumn {
            item {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    text = stringRes(R.string.settings__voice_models__summary),
                )
            }
            item {
                Text(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    text = stringRes(R.string.settings__voice_models__installed_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (isImporting) {
                item {
                    Text(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        text = stringRes(R.string.settings__voice_models__importing),
                        fontStyle = FontStyle.Italic,
                    )
                }
            } else if (languages.isEmpty()) {
                item {
                    Text(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        text = stringRes(R.string.settings__voice_models__empty),
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
            items(languages) { language ->
                JetPrefListItem(
                    text = languageName(language),
                    trailing = {
                        FlorisIconButton(
                            onClick = {
                                voskModels.delete(language)
                                languages = voskModels.installedLanguages()
                            },
                            icon = Icons.Default.Delete,
                        )
                    },
                )
            }
            item {
                Text(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    text = stringRes(R.string.settings__voice_models__download_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(VoskModelCatalog, key = { it.name }) { model ->
                val progress = downloads[model.name]
                val noteText = model.note?.let { stringRes(it) }
                val sizeText = stringRes(R.string.settings__voice_models__size, "size" to model.sizeMb)
                JetPrefListItem(
                    text = languageName(model.language),
                    secondaryText = when {
                        progress != null -> stringRes(
                            R.string.settings__voice_models__downloading, "percent" to (progress * 100).toInt(),
                        )
                        noteText != null -> "$noteText · $sizeText"
                        else -> sizeText
                    },
                    trailing = {
                        if (progress != null) {
                            FlorisIconButton(onClick = { VoskModelDownloader.cancel(model) }, icon = Icons.Default.Close)
                        } else {
                            FlorisIconButton(
                                onClick = {
                                    VoskModelDownloader.start(voskModels, model) { result ->
                                        result.onSuccess {
                                            appContext.showLongToastSync(
                                                R.string.settings__voice_models__import_success,
                                                "language" to languageName(model.language),
                                            )
                                        }.onFailure { error ->
                                            flogError { "Vosk model download failed: $error" }
                                            appContext.showLongToastSync(R.string.settings__voice_models__download_failed)
                                        }
                                    }
                                },
                                icon = Icons.Default.Download,
                            )
                        }
                    },
                )
            }
        }
    }
}

private fun languageName(language: String): String = FlorisLocale.from(language).displayName()

private fun displayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}
