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

package dev.patrickgold.florisboard.app.settings.voice

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.enumDisplayEntriesOf
import dev.patrickgold.florisboard.ime.voice.VoiceInputMode
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import org.florisboard.lib.compose.FlorisInfoCard
import org.florisboard.lib.compose.stringRes

@Composable
fun VoiceInputScreen() = FlorisScreen {
    title = stringRes(R.string.settings__voice_input__title)
    previewFieldVisible = false

    content {
        FlorisInfoCard(
            modifier = Modifier.padding(8.dp),
            text = stringRes(R.string.settings__voice_input__info),
        )

        SwitchPreference(
            prefs.voiceInput.useBuiltInVoiceInput,
            title = stringRes(R.string.pref__voice_input__use_built_in__label),
            summary = stringRes(R.string.pref__voice_input__use_built_in__summary),
        )
        ListPreference(
            prefs.voiceInput.mode,
            title = stringRes(R.string.pref__voice_input__mode__label),
            entries = enumDisplayEntriesOf(VoiceInputMode::class),
            enabledIf = { prefs.voiceInput.useBuiltInVoiceInput isEqualTo true },
        )
        SwitchPreference(
            prefs.voiceInput.autoSpace,
            title = stringRes(R.string.pref__voice_input__auto_space__label),
            summary = stringRes(R.string.pref__voice_input__auto_space__summary),
            enabledIf = { prefs.voiceInput.useBuiltInVoiceInput isEqualTo true },
        )
    }
}
