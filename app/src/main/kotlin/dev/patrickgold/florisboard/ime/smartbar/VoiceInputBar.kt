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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.ime.voice.VoiceInputError
import dev.patrickgold.florisboard.ime.voice.VoiceInputState
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.voiceInputManager
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * The bar shown in place of the smartbar while dictation is running. It reports what the recognizer is
 * currently hearing and lets the user stop or abort, all without the keyboard going away.
 */
@Composable
fun VoiceInputBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val voiceInputManager by context.voiceInputManager()

    val state by voiceInputManager.state.collectAsState()
    val partialText by voiceInputManager.partialText.collectAsState()
    val soundLevel by voiceInputManager.soundLevel.collectAsState()

    // Once dictation has fully ended, drop back to the normal smartbar. Errors stay up until dismissed so
    // the user gets a chance to read why nothing was typed.
    LaunchedEffect(state) {
        if (state == VoiceInputState.Inactive) {
            keyboardManager.activeState.isVoiceInputActive = false
        }
    }

    val micAlpha by animateFloatAsState(
        targetValue = if (state == VoiceInputState.Listening) 0.4f + soundLevel * 0.6f else 1f,
        label = "micAlpha",
    )

    val label = when (val current = state) {
        VoiceInputState.Listening -> partialText.ifBlank { stringRes(R.string.voice_input__listening) }
        VoiceInputState.Processing -> partialText.ifBlank { stringRes(R.string.voice_input__processing) }
        VoiceInputState.Inactive -> ""
        is VoiceInputState.Error -> when (current.reason) {
            VoiceInputError.NOT_AVAILABLE -> stringRes(R.string.voice_input__error_not_available)
            VoiceInputError.NO_PERMISSION -> stringRes(R.string.voice_input__error_no_permission)
            VoiceInputError.NETWORK -> stringRes(R.string.voice_input__error_network)
            VoiceInputError.NO_SPEECH -> stringRes(R.string.voice_input__error_no_speech)
            VoiceInputError.FAILED -> stringRes(R.string.voice_input__error_failed)
        }
    }

    SnyggRow(
        elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
        modifier = modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SnyggIcon(
            modifier = Modifier
                .sizeIn(maxHeight = FlorisImeSizing.smartbarHeight)
                .aspectRatio(1f)
                .padding(8.dp)
                .alpha(micAlpha),
            imageVector = Icons.Default.Mic,
        )
        SnyggText(
            elementName = "${FlorisImeUi.SmartbarCandidateWord.elementName}-text",
            modifier = Modifier.weight(1f),
            text = label,
        )
        if (state == VoiceInputState.Listening || state == VoiceInputState.Processing) {
            SnyggIconButton(
                elementName = FlorisImeUi.SmartbarSharedActionsToggle.elementName,
                onClick = { keyboardManager.stopVoiceInput() },
                modifier = Modifier.sizeIn(maxHeight = FlorisImeSizing.smartbarHeight).aspectRatio(1f),
            ) {
                SnyggIcon(imageVector = Icons.Default.Stop)
            }
        }
        SnyggIconButton(
            elementName = FlorisImeUi.SmartbarSharedActionsToggle.elementName,
            onClick = { keyboardManager.cancelVoiceInput() },
            modifier = Modifier.sizeIn(maxHeight = FlorisImeSizing.smartbarHeight).aspectRatio(1f),
        ) {
            SnyggIcon(imageVector = Icons.Default.Close)
        }
    }
}
