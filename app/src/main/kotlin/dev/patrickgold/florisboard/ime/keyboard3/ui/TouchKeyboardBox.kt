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

package dev.patrickgold.florisboard.ime.keyboard3.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard3.ImeController
import dev.patrickgold.florisboard.lib.toIntOffset

@Composable
fun TouchKeyboardBox(
    imeController: ImeController,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        val prefs by FlorisPreferenceStore
        val density = LocalDensity.current

        val keyboardWidthDp = with(density) { constraints.maxWidth.toDp() }
        val keyboardRowHeightDp = FlorisImeSizing.keyboardRowBaseHeight

        val imeState by imeController.activeState.collectAsState()
        val model by remember { derivedStateOf(referentialEqualityPolicy()) { imeState.model } }
        val touchLayerId by remember { derivedStateOf { imeState.touchLayerId } }

        val computedLayout = remember(model, density, keyboardWidthDp, keyboardRowHeightDp, touchLayerId) {
            doComputeTouchKeyboard(model, density, keyboardWidthDp, keyboardRowHeightDp, touchLayerId)
        }
        val computedLayoutState = rememberUpdatedState(computedLayout)

        if (computedLayout == null) {
            Text("Computed layout is null :(")
            return@BoxWithConstraints
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { computedLayout.bounds.height.toDp() })
                .touchKeyboardInput(
                    imeController = imeController,
                    resolveKey = { computedLayoutState.value?.findKeyByOffset(it.toIntOffset()) },
                ),
        ) {
            for (computedKey in computedLayout.keys) {
                if (computedKey.data.gap) {
                    continue
                }
                key(computedKey) {
                    TouchKeyBox(
                        computedKey,
                        imeController,
                    )
                }
            }
        }
    }
}
