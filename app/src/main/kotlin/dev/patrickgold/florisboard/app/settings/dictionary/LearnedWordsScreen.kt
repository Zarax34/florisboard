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

package dev.patrickgold.florisboard.app.settings.dictionary

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.patrickgold.florisboard.ime.nlp.latin.UserWordModel
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import dev.patrickgold.jetpref.material.ui.JetPrefListItem
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.FlorisChip
import org.florisboard.lib.compose.FlorisIconButton
import org.florisboard.lib.compose.stringRes

/** Shows what the keyboard learned from the user's typing and lets them forget single words or everything. */
@Composable
fun LearnedWordsScreen() = FlorisScreen {
    title = stringRes(R.string.settings__learned_words__title)
    previewFieldVisible = false
    scrollable = false

    val context = LocalContext.current
    val nlpManager by context.nlpManager()
    val scope = rememberCoroutineScope()

    var languages by remember { mutableStateOf(emptyList<String>()) }
    var language by remember { mutableStateOf<String?>(null) }
    var words by remember { mutableStateOf(emptyList<Pair<String, Int>>()) }
    var showClearDialog by remember { mutableStateOf(false) }

    suspend fun reload() {
        val provider = nlpManager.latinProvider()
        languages = provider.learnedLanguages()
        val selected = language?.takeIf { it in languages } ?: languages.firstOrNull()
        language = selected
        words = selected?.let { provider.learnedWords(it) }.orEmpty()
    }

    actions {
        if (words.isNotEmpty()) {
            FlorisIconButton(onClick = { showClearDialog = true }, icon = Icons.Default.DeleteSweep)
        }
    }

    content {
        LaunchedEffect(Unit) { reload() }

        LazyColumn {
            item {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    text = stringRes(R.string.settings__learned_words__summary, "count" to UserWordModel.LEARN_THRESHOLD),
                )
            }
            if (languages.size > 1) {
                item {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (lang in languages) {
                            FlorisChip(
                                text = FlorisLocale.from(lang).displayName(),
                                selected = lang == language,
                                onClick = {
                                    language = lang
                                    scope.launch { reload() }
                                },
                            )
                        }
                    }
                }
            }
            if (words.isEmpty()) {
                item {
                    Text(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        text = stringRes(R.string.settings__learned_words__empty),
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
            items(words, key = { it.first }) { (word, count) ->
                JetPrefListItem(
                    text = word,
                    secondaryText = stringRes(
                        if (count >= UserWordModel.LEARN_THRESHOLD) {
                            R.string.settings__learned_words__learned
                        } else {
                            R.string.settings__learned_words__counted
                        },
                        "count" to count,
                    ),
                    trailing = {
                        FlorisIconButton(
                            onClick = {
                                val lang = language ?: return@FlorisIconButton
                                scope.launch {
                                    nlpManager.latinProvider().forgetWord(lang, word)
                                    reload()
                                }
                            },
                            icon = Icons.Default.Delete,
                        )
                    },
                )
            }
        }

        if (showClearDialog) {
            JetPrefAlertDialog(
                title = stringRes(R.string.settings__learned_words__clear_title),
                confirmLabel = stringRes(R.string.action__delete),
                onConfirm = {
                    showClearDialog = false
                    val lang = language ?: return@JetPrefAlertDialog
                    scope.launch {
                        nlpManager.latinProvider().clearLearnedWords(lang)
                        reload()
                    }
                },
                dismissLabel = stringRes(R.string.action__cancel),
                onDismiss = { showClearDialog = false },
            ) {
                Text(text = stringRes(R.string.settings__learned_words__clear_message))
            }
        }
    }
}
