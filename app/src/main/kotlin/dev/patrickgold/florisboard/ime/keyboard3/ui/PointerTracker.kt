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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard3.ImeController
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.k3lp.model.layer.K3LayerId
import kotlin.math.pow

data class TrackedPointer(
    val id: PointerId,
    val down: PointerInputChange,
    val downLayerId: K3LayerId,
    val downKey: TouchKey,
    val peekLayerId: K3LayerId?,
    val peekKey: TouchKey?,
    val peekLine: PeekLine?,
    val peekMustSwitchBack: Boolean,
    val currKey: TouchKey?,
)

data class PeekLine(
    val start: Offset,
    val end: Offset,
)

@Composable
fun rememberPointerTracker(
    touchKeyboard: TouchKeyboard,
): PointerTracker {
    val prefs by FlorisPreferenceStore
    val density = LocalDensity.current
    val imeController = LocalImeController.current
    val scope = rememberCoroutineScope()

    // TODO make configurable
    val peekDistanceSqMin = with(density) { 30.dp.toPx().pow(2) }

    return remember(touchKeyboard) {
        PointerTracker(touchKeyboard, imeController, scope, peekDistanceSqMin)
    }
}

class PointerTracker(
    val touchKeyboard: TouchKeyboard,
    val imeController: ImeController,
    val scope: CoroutineScope,
    val peekDistanceSqMin: Float,
) {
    val trackedPointers = mutableStateMapOf<PointerId, TrackedPointer>()

    fun onDown(down: PointerInputChange) {
        val downLayerId = imeController.snapshotState().touchLayerId
        val downKey = touchKeyboard.findKey(downLayerId, down.position) ?: return
        down.consume()

        val trackedPointer = TrackedPointer(
            id = down.id,
            down = down,
            downLayerId = downLayerId,
            downKey = downKey,
            peekLayerId = downKey.data.layerId.takeIf { trackedPointers.isEmpty() },
            peekKey = null,
            peekLine = null,
            peekMustSwitchBack = false,
            currKey = downKey,
        )
        require(!trackedPointers.contains(trackedPointer.id))
        trackedPointers[trackedPointer.id] = trackedPointer
        if (downKey.data.layerId == null) {
            downKey.numPointersFocused.update { it + 1 }
        }

        if (trackedPointer.peekLayerId != null) {
            imeController.updateStateBlocking {
                switchTouchLayer(trackedPointer.peekLayerId)
            }
        }
    }

    fun onMove(move: PointerInputChange) {
        val trackedPointer = trackedPointers[move.id] ?: return
        move.consume()
        if (trackedPointer.peekLayerId != null) {
            val distanceSq = (move.position - trackedPointer.down.position).getDistanceSquared()
            if (trackedPointer.peekLine != null || distanceSq >= peekDistanceSqMin) {
                val oldPeekKey = trackedPointer.peekKey
                val newPeekKey = touchKeyboard.findKey(trackedPointer.peekLayerId, move.position)
                if (newPeekKey !== oldPeekKey) {
                    oldPeekKey?.numPointersFocused?.update { it - 1 }
                    newPeekKey?.numPointersFocused?.update { it + 1 }
                }
                trackedPointers[trackedPointer.id] = trackedPointer.copy(
                    peekKey = newPeekKey,
                    peekLine = PeekLine(trackedPointer.down.position, move.position),
                    peekMustSwitchBack = true,
                )
            }
        }
    }

    fun onUp(up: PointerInputChange) {
        val trackedPointer = trackedPointers[up.id] ?: return
        if (trackedPointer.downKey.data.layerId == null) {
            trackedPointer.downKey.numPointersFocused.update { it - 1 }
        }
        up.consume()
        scope.launch {
            imeController.updateState {
                if (trackedPointer.peekLayerId != null) {
                    if (trackedPointer.peekMustSwitchBack) {
                        switchTouchLayer(trackedPointer.downLayerId)
                    }
                    if (trackedPointer.peekKey != null) {
                        trackedPointer.peekKey.numPointersFocused.update { it - 1 }
                        trackedPointer.peekKey.data.output?.let { emit(it) }
                    }
                } else {
                    trackedPointer.currKey?.data?.output?.let { emit(it) }
                    for (otherId in trackedPointers.keys.toList()) {
                        val otherTp = trackedPointers[otherId]!!
                        if (otherTp.peekLayerId != null) {
                            trackedPointers[otherId] = otherTp.copy(peekMustSwitchBack = true)
                        }
                    }
                }
            }
        }
        trackedPointers.remove(trackedPointer.id)
    }

    fun onCancel(id: PointerId) {
        val trackedPointer = trackedPointers[id]
        requireNotNull(trackedPointer)
        trackedPointer.currKey?.numPointersFocused?.update { it - 1 }
        trackedPointer.peekKey?.numPointersFocused?.update { it - 1 }
        if (trackedPointer.peekLayerId != null) {
            scope.launch {
                imeController.updateState {
                    switchTouchLayer(trackedPointer.downLayerId)
                }
            }
        }
        trackedPointers.remove(id)
    }
}
