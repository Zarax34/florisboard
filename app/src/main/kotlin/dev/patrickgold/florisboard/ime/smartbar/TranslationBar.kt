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

package dev.patrickgold.florisboard.ime.smartbar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.translationManager
import org.florisboard.lib.compose.florisHorizontalScroll
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * The bar shown in place of the smartbar after the translate action was triggered. It lets the user pick
 * which language to translate the current selection (or the whole field) into, without leaving the keyboard.
 *
 * Languages whose offline pack is already downloaded are listed first; the rest are marked with a download
 * icon to make clear that picking them needs a network connection.
 */
@Composable
fun TranslationBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val translationManager by context.translationManager()

    val downloadedLanguages by translationManager.downloadedLanguages.collectAsState()
    val sourceLanguage = remember { keyboardManager.activeTranslationSourceLanguage() }
    val languages = remember(downloadedLanguages, sourceLanguage) {
        translationManager.supportedLanguages
            .filter { it != sourceLanguage }
            .sortedWith(compareBy({ it !in downloadedLanguages }, { translationManager.shortNameFor(it) }))
    }

    SnyggRow(
        elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
        modifier = modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SnyggIconButton(
            elementName = FlorisImeUi.SmartbarSharedActionsToggle.elementName,
            onClick = { keyboardManager.activeState.isTranslationBarVisible = false },
            modifier = Modifier.sizeIn(maxHeight = FlorisImeSizing.smartbarHeight).aspectRatio(1f),
        ) {
            SnyggIcon(imageVector = Icons.Default.Close)
        }
        SnyggRow(
            modifier = Modifier
                .fillMaxSize()
                .florisHorizontalScroll(scrollbarHeight = CandidatesRowScrollbarHeight),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (language in languages) {
                val needsDownload = language !in downloadedLanguages
                SnyggRow(
                    elementName = FlorisImeUi.SmartbarCandidateWord.elementName,
                    modifier = Modifier
                        .fillMaxHeight()
                        .clickable { keyboardManager.translateInto(language) }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (needsDownload) {
                        SnyggBox(elementName = "${FlorisImeUi.SmartbarCandidateWord.elementName}-icon") {
                            SnyggIcon(imageVector = Icons.Default.CloudDownload)
                        }
                    }
                    SnyggText(
                        elementName = "${FlorisImeUi.SmartbarCandidateWord.elementName}-text",
                        text = translationManager.shortNameFor(language),
                    )
                }
            }
        }
    }
}
