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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import org.florisboard.lib.compose.toMm
import org.k3lp.lib.text.K3StringOrDescriptor
import org.k3lp.lib.text.asK3String
import org.k3lp.model.K3Model
import org.k3lp.model.flick.K3Flick
import org.k3lp.model.key.K3Key
import org.k3lp.model.layer.K3LayerId
import kotlin.math.roundToInt

data class TouchKeyboard(
    val bounds: Rect,
    val layers: Map<K3LayerId, TouchLayer>,
) {
    fun findKey(layerId: K3LayerId, position: Offset): TouchKey? {
        val layer = layers[layerId]
        if (layer == null || !bounds.contains(position)) {
            return null
        }
        for (key in layer.keys) {
            if (key.bounds.contains(position)) {
                return key
            }
        }
        return null
    }
}

data class TouchLayer(
    val keys: List<TouchKey>,
) {
    companion object {
        val Empty = TouchLayer(emptyList())
    }
}

data class TouchKey(
    val bounds: Rect,
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
): TouchKeyboard {
    val deviceWidthMm = with(density) { rowWidthDp.toMm() }
    val layers = model.layersByForm.touch.lastOrNull { it.minDeviceWidth <= deviceWidthMm }?.layers
    val rowCount = layers?.maxOf { (_, layer) -> layer.rows.size }?.coerceAtLeast(4) ?: 4
    val touchKeyboardBounds = Rect(
        offset = Offset.Zero,
        size = Size(
            width = with(density) { rowWidthDp.toPx() },
            height = with(density) { (rowHeightDp * rowCount).toPx() },
        )
    )
    if (layers == null) {
        return TouchKeyboard(
            bounds = touchKeyboardBounds,
            layers = mapOf(
                K3LayerId.BASE to TouchLayer.Empty,
            ),
        )
    }

    val touchLayers = layers.mapValues { (_, layer) ->
        val rows = layer.rows.map {
            it.map { keyId ->
                val key = model.keys.byKeyId[keyId]
                requireNotNull(key) { "unexpected runtime error: model contract broken" }
            }
        }
        val keyHeightPx = touchKeyboardBounds.height / rows.size
        val touchKeys = mutableListOf<TouchKey>()
        var currentY = 0f
        for (row in rows) {
            val desiredWeightSum = 10f
            val fullWeightSum = row.fold(0f) { acc, key -> acc + key.width.toFloat() }
            val stretchWeightSum = row.fold(0f) { acc, key -> acc + (if (key.stretch) key.width.toFloat() else 0f) }
            val nonStretchSum = fullWeightSum - stretchWeightSum
            val mayGrowKeys = fullWeightSum <= desiredWeightSum
            val mayStretchKeys = mayGrowKeys && stretchWeightSum != 0f
            val desiredKeyWidth = when {
                mayGrowKeys -> touchKeyboardBounds.width / 10
                else -> touchKeyboardBounds.width * desiredWeightSum / fullWeightSum / 10
            }
            val desiredStretchKeyWidth = when {
                mayGrowKeys && mayStretchKeys -> {
                    touchKeyboardBounds.width * (desiredWeightSum - nonStretchSum) / desiredWeightSum
                }
                else -> 0f
            }
            var keyWidthSum = 0f
            val keyWidths = mutableListOf<Float>()
            for (key in row) {
                val keyWidthPx = when {
                    mayStretchKeys && key.stretch -> desiredStretchKeyWidth * key.width.toFloat()
                        .roundToInt() / stretchWeightSum

                    else -> desiredKeyWidth * key.width.toFloat()
                }
                keyWidths.add(keyWidthPx)
                keyWidthSum += keyWidthPx
            }
            var currentX = when {
                mayGrowKeys && !mayStretchKeys -> (touchKeyboardBounds.width - keyWidthSum + 1) / 2
                else -> 0f
            }
            for ((i, key) in row.withIndex()) {
                val keyWidthPx = keyWidths[i]
                val keyBoundsPx = Rect(
                    offset = Offset(currentX, currentY),
                    size = Size(keyWidthPx, keyHeightPx),
                )
                val touchKey = TouchKey(
                    bounds = keyBoundsPx,
                    label = doComputeKeyDisplay(model, key),
                    data = key,
                    flick = key.flickId?.let { model.flicks.byFlickId[it] },
                    numPointersFocused = mutableIntStateOf(0),
                )
                touchKeys.add(touchKey)
                currentX += keyWidthPx
            }
            currentY += keyHeightPx
        }
        TouchLayer(touchKeys.toList())
    }
    return TouchKeyboard(
        bounds = touchKeyboardBounds,
        layers = touchLayers,
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
