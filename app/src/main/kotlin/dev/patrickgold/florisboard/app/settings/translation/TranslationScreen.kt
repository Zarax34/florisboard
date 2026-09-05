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

package dev.patrickgold.florisboard.app.settings.translation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.translation.TranslationManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.translationManager
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.datastore.ui.listPrefEntries
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.FlorisInfoCard
import org.florisboard.lib.compose.stringRes

@Composable
fun TranslationScreen() = FlorisScreen {
    title = stringRes(R.string.settings__translation__title)
    previewFieldVisible = false

    content {
        val context = LocalContext.current
        val translationManager by context.translationManager()
        val coroutineScope = rememberCoroutineScope()

        val downloadedLanguages by translationManager.downloadedLanguages.collectAsState()
        val busyLanguages by translationManager.busyLanguages.collectAsState()
        val supportedLanguages = translationManager.supportedLanguages

        FlorisInfoCard(
            modifier = Modifier.padding(8.dp),
            text = stringRes(R.string.settings__translation__info),
        )

        PreferenceGroup(title = stringRes(R.string.settings__translation__languages_title)) {
            ListPreference(
                prefs.translation.sourceLanguage,
                title = stringRes(R.string.pref__translation__source_language__label),
                entries = listPrefEntries {
                    entry(
                        key = TranslationManager.SOURCE_LANGUAGE_SUBTYPE,
                        label = stringRes(R.string.pref__translation__source_language__keyboard_language),
                    )
                    for (language in supportedLanguages) {
                        entry(key = language, label = translationManager.displayNameFor(language))
                    }
                },
            )
            ListPreference(
                prefs.translation.targetLanguage,
                title = stringRes(R.string.pref__translation__target_language__label),
                entries = listPrefEntries {
                    for (language in supportedLanguages) {
                        entry(key = language, label = translationManager.displayNameFor(language))
                    }
                },
            )
            SwitchPreference(
                prefs.translation.downloadOverWifiOnly,
                title = stringRes(R.string.pref__translation__download_over_wifi_only__label),
                summary = stringRes(R.string.pref__translation__download_over_wifi_only__summary),
            )
        }

        PreferenceGroup(title = stringRes(R.string.settings__translation__packs_title)) {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                text = stringRes(R.string.settings__translation__packs_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (language in supportedLanguages) {
                val isDownloaded = language in downloadedLanguages
                val isBusy = language in busyLanguages
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isBusy) {
                            coroutineScope.launch {
                                if (isDownloaded) {
                                    translationManager.deleteLanguage(language)
                                } else {
                                    translationManager.downloadLanguage(language)
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = translationManager.displayNameFor(language),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = if (isDownloaded) {
                                stringRes(R.string.settings__translation__pack_downloaded)
                            } else {
                                stringRes(R.string.settings__translation__pack_not_downloaded)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        isBusy -> {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                        isDownloaded -> {
                            IconButton(onClick = {
                                coroutineScope.launch { translationManager.deleteLanguage(language) }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringRes(
                                        R.string.settings__translation__delete_pack
                                    ),
                                )
                            }
                        }
                        else -> {
                            IconButton(onClick = {
                                coroutineScope.launch { translationManager.downloadLanguage(language) }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = stringRes(
                                        R.string.settings__translation__download_pack
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
