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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.ime.window.LocalWindowController
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.k3lp.model.layer.K3LayerId
import kotlin.math.roundToInt

@Composable
fun ImeKeyboardLayout(
    modifier: Modifier = Modifier,
) {
    val prefs by FlorisPreferenceStore
    val density = LocalDensity.current
    val imeController = LocalImeController.current
    val windowController = LocalWindowController.current

    val imeState by imeController.activeState.collectAsState()
    val model by remember { derivedStateOf { imeState.model } }
    val touchLayerId by remember { derivedStateOf { imeState.touchLayerId } }

    val windowSpec by windowController.activeWindowSpec.collectAsState()
    val keyMargin = remember(windowSpec.keyMarginH, windowSpec.keyMarginV) {
        PaddingValues(
            horizontal = windowSpec.keyMarginH.takeOrElse { 0.dp },
            vertical = windowSpec.keyMarginV.takeOrElse { 0.dp },
        )
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val keyboardWidthDp = with(density) { constraints.maxWidth.toDp() }
        val keyboardRowHeightDp = FlorisImeSizing.keyboardRowBaseHeight

        // TODO this must be cached beyond this composable to prevent flashes
        //   during media/clipboard -> text switch
        var activeTouchKeyboard by remember {
            val initialSize = Size(
                width = with(density) { keyboardWidthDp.toPx() },
                height = with(density) { (keyboardRowHeightDp * 4).toPx() },
            )
            val initialBounds = Rect(Offset.Zero, initialSize)
            mutableStateOf(TouchKeyboard.empty(initialBounds))
        }
        val activeTouchLayer by remember {
            derivedStateOf {
                activeTouchKeyboard.layers[touchLayerId]
                    ?: activeTouchKeyboard.layers[K3LayerId.BASE]
                    ?: TouchLayer.Empty
            }
        }

        // TODO make configurable
        val peekLineWidthPx = with(density) { 8.dp.toPx() }
        val pointerTracker = rememberPointerTracker(activeTouchKeyboard)

        LaunchedEffect(model, density, keyboardWidthDp, keyboardRowHeightDp) {
            activeTouchKeyboard = doComputeTouchKeyboard(model, density, keyboardWidthDp, keyboardRowHeightDp)
        }

        Box(
            modifier = Modifier
                .layout { measurable, _ ->
                    val width = activeTouchKeyboard.bounds.width.roundToInt()
                    val height = activeTouchKeyboard.bounds.height.roundToInt()
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
            for (touchKey in activeTouchLayer.keys) {
                if (touchKey.data.gap) {
                    continue
                }
                TouchKeyboardKeyBox(touchKey, keyMargin)
            }
        }
    }
}

@Composable
private fun TouchKeyboardKeyBox(
    touchKey: TouchKey,
    keyMargin: PaddingValues = PaddingValues.Zero,
) {
    val label = touchKey.label // TODO for space replace label by active subtype language
    val output = touchKey.data.output
    val attributes: SnyggQueryAttributes = remember(output) {
        buildMap {
            if (output != null) {
                put(FlorisImeUi.Attr.Output, output.asAttrValue())
            }
        }
    }

    val numPointersFocused by touchKey.numPointersFocused.collectAsState()
    val selector by remember {
        derivedStateOf {
            if (numPointersFocused > 0) SnyggSelector.PRESSED else SnyggSelector.NONE
        }
    }

    SnyggBox(
        FlorisImeUi.Key.elementName,
        attributes = attributes,
        selector = selector,
        modifier = Modifier
            .layout { measurable, _ ->
                val width = touchKey.bounds.width.roundToInt()
                val height = touchKey.bounds.height.roundToInt()
                val offset = touchKey.bounds.topLeft.round()
                val constraints = Constraints(width, width, height, height)
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(offset) }
            }
            .padding(keyMargin),
    ) {
        Label3(
            modifier = Modifier
                .wrapContentSize()
                .align(Alignment.Center),
            value = label,
        )
    }
}
