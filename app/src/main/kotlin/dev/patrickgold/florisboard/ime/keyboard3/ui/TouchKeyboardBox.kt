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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard3.ImeController
import org.k3lp.model.layer.K3LayerId
import kotlin.math.roundToInt

@Composable
fun TouchKeyboardBox(
    imeController: ImeController,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val prefs by FlorisPreferenceStore
        val density = LocalDensity.current

        val keyboardWidthDp = with(density) { constraints.maxWidth.toDp() }
        val keyboardRowHeightDp = FlorisImeSizing.keyboardRowBaseHeight

        val imeState by imeController.activeState.collectAsState()
        val model by remember { derivedStateOf(referentialEqualityPolicy()) { imeState.model } }
        val touchKeyboard = remember(model, density, keyboardWidthDp, keyboardRowHeightDp) {
            doComputeTouchKeyboard(model, density, keyboardWidthDp, keyboardRowHeightDp)
        }
        val activeTouchLayer by rememberUpdatedState(
            touchKeyboard.layers[imeState.touchLayerId] ?: touchKeyboard.layers[K3LayerId.BASE]
        )

        Box(
            modifier = Modifier
                .layout { measurable, _ ->
                    val width = touchKeyboard.bounds.width.roundToInt()
                    val height = touchKeyboard.bounds.height.roundToInt()
                    val constraints = Constraints(width, width, height, height)
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .touchKeyboardInput(
                    touchKeyboard = touchKeyboard,
                    imeController = imeController,
                ),
        ) {
            val touchLayer = activeTouchLayer
            if (touchLayer != null) {
                for (touchKey in touchLayer.keys) {
                    if (touchKey.data.gap) {
                        continue
                    }
                    TouchKeyBox(touchKey)
                }
            } else {
                Text("activeTouchLayer is null :(")
            }
        }
    }
}
