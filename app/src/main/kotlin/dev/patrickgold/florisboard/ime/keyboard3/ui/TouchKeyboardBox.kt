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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.k3lp.model.layer.K3LayerId
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun TouchKeyboardBox(
    modifier: Modifier = Modifier,
) {
    val prefs by FlorisPreferenceStore
    val density = LocalDensity.current
    val imeController = LocalImeController.current

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val keyboardWidthDp = with(density) { constraints.maxWidth.toDp() }
        val keyboardRowHeightDp = FlorisImeSizing.keyboardRowBaseHeight

        val imeState by imeController.activeState.collectAsState()
        val model by remember { derivedStateOf { imeState.model } }
        val touchKeyboard = remember(model, density, keyboardWidthDp, keyboardRowHeightDp) {
            doComputeTouchKeyboard(model, density, keyboardWidthDp, keyboardRowHeightDp)
        }
        val activeTouchLayer by rememberUpdatedState(
            touchKeyboard.layers[imeState.touchLayerId] ?: touchKeyboard.layers[K3LayerId.BASE]
        )

        // TODO make configurable
        val peekLineWidthPx = with(density) { 8.dp.toPx() }
        val peekDistanceSqMin = with(density) { 30.dp.toPx().pow(2) }

        val pointerTracker = remember(touchKeyboard) {
            PointerTracker(prefs, touchKeyboard, imeController, peekDistanceSqMin)
        }

        Box(
            modifier = Modifier
                .layout { measurable, _ ->
                    val width = touchKeyboard.bounds.width.roundToInt()
                    val height = touchKeyboard.bounds.height.roundToInt()
                    val constraints = Constraints(width, width, height, height)
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .pointerInput(Unit) {
                    val currentContext = currentCoroutineContext()
                    awaitPointerEventScope {
                        while (currentContext.isActive) {
                            // TODO this pointer logic is VERY KEEN on sending up, even survives
                            //  mouse leave&re-enter in the emulator => OOB checks
                            val event = awaitPointerEvent()
                            // TODO evaluate this cancellation logic
                            pointerTracker.trackedPointers.forEach { (id, _) ->
                                val change = event.changes.fastFirstOrNull { it.id == id }
                                if (change == null) {
                                    // TODO does snapshot map not throw ConcurrentModificationException ??
                                    pointerTracker.onCancel(id)
                                }
                            }
                            event.changes.fastForEach { change ->
                                if (change.changedToDown()) {
                                    pointerTracker.onDown(change)
                                } else if (change.changedToUp()) {
                                    pointerTracker.onUp(change)
                                } else if (!change.isConsumed) {
                                    pointerTracker.onMove(change)
                                }
                            }
                        }
                    }
                }
                .drawWithContent {
                    drawContent()
                    for ((_, trackedPointer) in pointerTracker.trackedPointers) {
                        val peekLine = trackedPointer.peekLine
                        if (trackedPointer.peekLine != null) {
                            drawLine(
                                color = Color.Red, // TODO customizable
                                start = peekLine.start,
                                end = peekLine.end,
                                strokeWidth = peekLineWidthPx,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
        ) {
            val touchLayer = activeTouchLayer
            if (touchLayer != null) {
                for (touchKey in touchLayer.keys) {
                    if (touchKey.data.gap) {
                        continue
                    }
                    key(touchKey) {
                        TouchKeyBox(touchKey)
                    }
                }
            } else {
                Text("activeTouchLayer is null :(")
            }
        }
    }
}
