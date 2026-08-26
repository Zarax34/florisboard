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

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.florisboard.lib.compose.toMm
import org.k3lp.lib.text.K3StringOrDescriptor
import org.k3lp.lib.text.asK3String
import org.k3lp.model.K3Model
import org.k3lp.model.flick.K3Flick
import org.k3lp.model.key.K3Key
import org.k3lp.model.layer.K3LayerId
import kotlin.math.roundToInt

const val PRECISION_FACTOR = 1000

data class TouchKeyboard(
    val bounds: IntRect,
    val keys: List<TouchKey>,
) {
    fun findKeyByOffset(offset: IntOffset): TouchKey? {
        if (!bounds.contains(offset)) {
            return null
        }
        for (key in keys) {
            if (key.bounds.contains(offset)) {
                return key
            }
        }
        return null
    }
}

data class TouchKey(
    val bounds: IntRect,
    val label: K3StringOrDescriptor,
    val data: K3Key,
    val flick: K3Flick?,
    val numPointersFocused: MutableIntState,
)

fun doComputeTouchKeyboard(
    model: K3Model,
    density: Density,
    rowWidthDp: Dp,
    rowHeightDp: Dp,
    layerId: K3LayerId,
): TouchKeyboard? {
    val deviceWidthMm = with(density) { rowWidthDp.toMm() }
    val layerList = model.layersByForm.touch.lastOrNull { it.minDeviceWidth <= deviceWidthMm } ?: return null
    val layer = layerList.layers[layerId] ?: return null

    val rows = layer.rows.map {
        it.map { keyId ->
            val key = model.keys.byKeyId[keyId]
            requireNotNull(key) { "unexpected runtime error: model contract broken" }
        }
    }
    val totalHeightDp = rowHeightDp * rows.size.coerceAtLeast(4)
    val totalWidthPx = with(density) { rowWidthDp.toPx() }.toInt()
    val totalHeightPx = with(density) { totalHeightDp.toPx() }.toInt()

    val desiredKeyHeightPx = totalHeightPx / rows.size
    val keyHeightRemainder = totalHeightPx % rows.size
    val computedKeys = mutableListOf<TouchKey>()
    var currentY = 0
    for ((rowIndex, row) in rows.withIndex()) {
        val desiredWeightSum = 1 * 10 * PRECISION_FACTOR
        val fullWeightSum = row.sumOf { it.width.times(PRECISION_FACTOR).roundToInt() }
        val stretchWeightSum = row.sumOf { if (it.stretch) it.width.times(PRECISION_FACTOR).roundToInt() else 0 }
        val nonStretchSum = fullWeightSum - stretchWeightSum
        val mayGrowKeys = fullWeightSum <= desiredWeightSum
        val mayStretchKeys = mayGrowKeys && stretchWeightSum != 0
        val desiredKeyWidth = when {
            mayGrowKeys -> totalWidthPx / 10
            else -> totalWidthPx * desiredWeightSum / fullWeightSum / 10
        }
        val desiredStretchKeyWidth = when {
            mayGrowKeys && mayStretchKeys -> {
                totalWidthPx * (desiredWeightSum - nonStretchSum) / desiredWeightSum
            }
            else -> 0
        }
        var keyWidthSum = 0
        val keyWidths = mutableListOf<Int>()
        for (key in row) {
            val keyWidthPx = when {
                mayStretchKeys && key.stretch -> desiredStretchKeyWidth * key.width.times(PRECISION_FACTOR).roundToInt() / stretchWeightSum
                else -> desiredKeyWidth * key.width.times(PRECISION_FACTOR).roundToInt() / PRECISION_FACTOR
            }
            keyWidths.add(keyWidthPx)
            keyWidthSum += keyWidthPx
        }
        var currentX = when {
            mayGrowKeys && !mayStretchKeys -> (totalWidthPx - keyWidthSum + 1) / 2
            else -> 0
        }
        val keyHeightPx = desiredKeyHeightPx + if (rowIndex < keyHeightRemainder) 1 else 0
        for ((i, key) in row.withIndex()) {
            val keyWidthPx = keyWidths[i]
            val touchKey = TouchKey(
                bounds = IntRect(
                    offset = IntOffset(currentX, currentY),
                    size = IntSize(keyWidthPx, keyHeightPx),
                ),
                label = doComputeKeyDisplay(model, key),
                data = key,
                flick = key.flickId?.let { model.flicks.byFlickId[it] },
                numPointersFocused = mutableIntStateOf(0),
            )
            computedKeys.add(touchKey)
            currentX += keyWidthPx
        }
        currentY += keyHeightPx
    }
    return TouchKeyboard(
        bounds = IntRect(IntOffset.Zero, IntSize(totalWidthPx, totalHeightPx)),
        keys = computedKeys.toList(),
    )
}

fun doComputeKeyDisplay(model: K3Model, key: K3Key): K3StringOrDescriptor {
    val displayByKey = model.displays.byKeyId[key.id]
    if (displayByKey != null) {
        return displayByKey.display
    }
    if (key.output != null) {
        val displayByOut = model.displays.byOutput[key.output]
        if (displayByOut != null) {
            return displayByOut.display
        }
    }
    return key.output ?: key.id.value.asK3String()
}
