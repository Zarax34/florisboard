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

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import dev.patrickgold.florisboard.ime.keyboard3.ImeController
import kotlin.math.pow

private data class PeekLine(
    val touchKey: TouchKey?,
    val start: Offset,
    val end: Offset,
)

fun Modifier.touchKeyboardInput(
    imeController: ImeController,
    resolveKey: (Offset) -> TouchKey?,
): Modifier = composed {
    val density = LocalDensity.current
    val peekLineWidthPx = with(density) { 8.dp.toPx() }
    val peekDistanceSqMin = with(density) { 30.dp.toPx().pow(2) }

    var currPeekLine by remember { mutableStateOf<PeekLine?>(null) }

    pointerInput(Unit) {
        while (true) {
            awaitPointerEventScope {
                val down = awaitFirstDown()
                val downKey = resolveKey(down.position) ?: return@awaitPointerEventScope
                down.consume()
                val downLayerId = imeController.snapshotState().touchLayerId
                val peekLayerId = downKey.data.layerId
                if (peekLayerId != null) {
                    imeController.updateStateBlocking {
                        switchTouchLayer(peekLayerId)
                    }
                }
                while (true) {
                    val event = awaitPointerEvent()
                    val curr = event.changes.fastFirstOrNull { it.id == down.id }
                    if (curr == null) {
                        // pointer was cancelled
                        if (peekLayerId != null) {
                            imeController.updateStateBlocking {
                                switchTouchLayer(downLayerId)
                            }
                        }
                        break
                    }
                    if (peekLayerId != null) {
                        val distanceSq = (curr.position - down.position).getDistanceSquared()
                        if (currPeekLine != null || distanceSq >= peekDistanceSqMin) {
                            val touchKey = resolveKey(curr.position)
                            currPeekLine = PeekLine(touchKey, down.position, curr.position)
                        }
                    }
                    if (curr.changedToUp()) {
                        curr.consume()
                        imeController.updateStateBlocking {
                            if (peekLayerId != null) {
                                if (currPeekLine != null) {
                                    switchTouchLayer(downLayerId)
                                    currPeekLine?.touchKey?.data?.output?.let { emit(it) }
                                }
                            } else {
                                downKey.data.output?.let { emit(it) }
                            }
                        }
                        break
                    } else {
                        curr.consume()
                    }
                }
                currPeekLine = null
            }
        }
    }
        .drawWithContent {
            drawContent()
            currPeekLine?.let { peekLine ->
                drawLine(
                    color = Color.Red,
                    start = peekLine.start,
                    end = peekLine.end,
                    strokeWidth = peekLineWidthPx,
                    cap = StrokeCap.Round,
                )
            }
        }
}
