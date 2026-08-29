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

package dev.patrickgold.florisboard.ime.keyboard3.touch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.flow.MutableStateFlow
import org.k3lp.lib.text.K3StringOrDescriptor
import org.k3lp.lib.text.asK3String
import org.k3lp.model.K3Model
import org.k3lp.model.flick.K3Flick
import org.k3lp.model.key.K3Key
import org.k3lp.model.layer.K3LayerId
import kotlin.math.roundToInt

class TouchKeyboard(
    val bounds: Rect,
    val layers: Map<K3LayerId, TouchLayer>,
    val rowCount: Int,
) {
    fun findKey(layerId: K3LayerId, position: Offset): TouchKey? {
        val layer = layers[layerId]
        if (layer == null || !bounds.contains(position)) {
            return null
        }
        // TODO improve runtime of this
        for (key in layer.keys) {
            if (key.bounds.contains(position)) {
                return key
            }
        }
        return null
    }

    companion object {
        val GenericBounds = Rect(Offset.Zero, Size(1f, 1f))

        val Empty = TouchKeyboard(
            bounds = GenericBounds,
            layers = mapOf(
                K3LayerId.BASE to TouchLayer.Empty,
            ),
            rowCount = 4,
        )
    }
}

class TouchLayer(
    val keys: List<TouchKey>,
) {
    companion object {
        val Empty = TouchLayer(emptyList())
    }
}

class TouchKey(
    val bounds: Rect,
    val label: K3StringOrDescriptor,
    val data: K3Key,
    val flick: K3Flick?,
    val numPointersFocused: MutableStateFlow<Int>,
)

fun doComputeTouchKeyboard(
    model: K3Model,
    deviceWidthMm: Int,
): TouchKeyboard {
    val layers = model.layersByForm.touch.lastOrNull { it.minDeviceWidth <= deviceWidthMm }
        ?.layers
        ?: return TouchKeyboard.Empty
    val rowCount = layers.maxOf { (_, layer) -> layer.rows.size }.coerceAtLeast(4)

    val touchLayers = layers.mapValues { (_, layer) ->
        val rows = layer.rows.map {
            it.map { keyId ->
                val key = model.keys.byKeyId[keyId]
                requireNotNull(key) { "unexpected runtime error: model contract broken" }
            }
        }
        val keyHeight = 1f / rows.size
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
                mayGrowKeys -> 1f / 10f
                else -> desiredWeightSum / fullWeightSum / 10f
            }
            val desiredStretchKeyWidth = when {
                mayGrowKeys && mayStretchKeys -> (desiredWeightSum - nonStretchSum) / desiredWeightSum
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
                mayGrowKeys && !mayStretchKeys -> (1f - keyWidthSum) / 2
                else -> 0f
            }
            for ((i, key) in row.withIndex()) {
                val keyWidthPx = keyWidths[i]
                val keyBoundsPx = Rect(
                    offset = Offset(currentX, currentY),
                    size = Size(keyWidthPx, keyHeight),
                )
                val touchKey = TouchKey(
                    bounds = keyBoundsPx,
                    label = doComputeKeyDisplay(model, key),
                    data = key,
                    flick = key.flickId?.let { model.flicks.byFlickId[it] },
                    numPointersFocused = MutableStateFlow(0),
                )
                touchKeys.add(touchKey)
                currentX += keyWidthPx
            }
            currentY += keyHeight
        }
        TouchLayer(touchKeys.toList())
    }
    return TouchKeyboard(
        bounds = TouchKeyboard.GenericBounds,
        layers = touchLayers,
        rowCount = rowCount,
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
