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

import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.round
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.ime.window.LocalWindowController
import dev.patrickgold.jetpref.datastore.model.collectAsState
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggText
import org.k3lp.lib.text.K3Descriptor
import org.k3lp.lib.text.K3String
import kotlin.math.roundToInt

@Composable
fun TouchKeyBox(
    touchKey: TouchKey,
) {
    val prefs by FlorisPreferenceStore
    val debugShowTouchBoundaries by prefs.devtools.showKeyTouchBoundaries.collectAsState()

    val windowController = LocalWindowController.current
    val windowSpec by windowController.activeWindowSpec.collectAsState()

//    val attributes = mapOf(
//        FlorisImeUi.Attr.Code to key.computedData.code,
//        FlorisImeUi.Attr.Mode to evaluator.keyboard.mode.toString(),
//        FlorisImeUi.Attr.ShiftState to evaluator.state.inputShiftState.toString(),
//    )
    var selector by remember { mutableStateOf(SnyggSelector.NONE) }
    val isSuitableForBasicPopup: Boolean = touchKey.data.output.let { output ->
        output != null && output is K3String
    }
    val isSuitableForExtendedPopup: Boolean = !touchKey.data.longPressKeyIds.isNullOrEmpty()

    SnyggBox(
        FlorisImeUi.Key.elementName,
        attributes = emptyMap(),
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
            .padding(windowSpec.keyMarginH, windowSpec.keyMarginV),
    ) {
        TouchKeyDisplay(
            computedKey = touchKey,
            modifier = Modifier.align(Alignment.Center),
        )
    }

//        if (isSuitableForBasicPopup && selector == SnyggSelector.PRESSED) {
//            TouchKeySimplePopupBox(
//                modifier = Modifier
//                    .requiredSize(
//                        width = visibleSize.width * 1.1f,
//                        height = visibleSize.height * 2.5f,
//                    )
//                    .offset(y = (visibleSize.height * -2.5f + visibleSize.height) / 2f),
//                attributes = emptyMap(), // TODO
//                shouldIndicateExtendedPopups = isSuitableForExtendedPopup,
//            ) {
//                TouchKeyDisplay(
//                    computedKey = touchKey,
//                    modifier = Modifier
//                        .requiredSize(visibleSize),
//                )
//            }
//        }
}

@Composable
fun TouchKeyDisplay(
    computedKey: TouchKey,
    modifier: Modifier = Modifier,
) {
    when (val label = computedKey.label) {
        is K3String -> {
            SnyggText(
                modifier = modifier
                    .wrapContentSize(),
                text = label.toText(),
            )
        }
        is K3Descriptor -> {
            DescriptorIcon(
                modifier = modifier
                    .wrapContentSize(),
                descriptor = label,
            )
        }
    }
}

private suspend fun AwaitPointerEventScope.determineKeyEventType(
    longPressDelay: Int,
): KeyEventType {
    var type = KeyEventType.CANCELLED
    try {
        withTimeout(longPressDelay.toLong()) {
            waitForUpOrCancellation()?.let {
                it.consume()
                type = KeyEventType.KEY_PRESS
            }
        }
    } catch (_: PointerEventTimeoutCancellationException) {
        type = KeyEventType.LONG_PRESS
    }
    return type
}

private enum class KeyEventType {
    CANCELLED,
    KEY_PRESS,
    LONG_PRESS,
}
