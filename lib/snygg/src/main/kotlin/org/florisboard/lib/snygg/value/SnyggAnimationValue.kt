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

package org.florisboard.lib.snygg.value

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val Duration = "duration"
private const val MsUnit = "ms"
private const val Factor = "factor"
private const val Angle = "angle"
private const val Offset = "offset"
private const val DpUnit = "dp"
private const val DegUnit = "deg"

/**
 * How long a style change on an element should take, e.g. `"120ms"`. Zero means the change is applied
 * instantly, which is the behavior of a stylesheet that does not mention transitions at all.
 */
data class SnyggMsDurationValue(val millis: Int) : SnyggValue {
    companion object : SnyggValueEncoder {
        override val spec = SnyggValueSpec {
            int(id = Duration, unit = MsUnit, numberPattern = """0|[1-9][0-9]*""".toRegex())
        }

        override fun defaultValue() = SnyggMsDurationValue(0)

        override fun serialize(v: SnyggValue) = runCatching<String> {
            require(v is SnyggMsDurationValue)
            require(v.millis >= 0)
            val map = snyggIdToValueMapOf(Duration to v.millis)
            return@runCatching spec.pack(map)
        }

        override fun deserialize(v: String) = runCatching<SnyggValue> {
            val map = snyggIdToValueMapOf()
            spec.parse(v, map)
            return@runCatching SnyggMsDurationValue(map.getInt(Duration))
        }
    }

    override fun encoder() = Companion
}

/**
 * A uniform scale factor applied to an element, e.g. `"0.94"` on a pressed key to make it dip. `1` is the
 * natural size. Combined with a transition duration the change is animated instead of snapping.
 */
data class SnyggScaleValue(val scale: Float) : SnyggValue {
    companion object : SnyggValueEncoder {
        override val spec = SnyggValueSpec {
            float(id = Factor, numberPattern = """(?:0|[1-9][0-9]*)(?:[.][0-9]*)?|[.][0-9]+""".toRegex())
        }

        override fun defaultValue() = SnyggScaleValue(1f)

        override fun serialize(v: SnyggValue) = runCatching<String> {
            require(v is SnyggScaleValue)
            require(v.scale >= 0f)
            val map = snyggIdToValueMapOf(Factor to v.scale)
            return@runCatching spec.pack(map)
        }

        override fun deserialize(v: String) = runCatching<SnyggValue> {
            val map = snyggIdToValueMapOf()
            spec.parse(v, map)
            return@runCatching SnyggScaleValue(map.getFloat(Factor))
        }
    }

    override fun encoder() = Companion
}

/**
 * A clockwise rotation in degrees, e.g. `"8deg"`. Negative values rotate counter-clockwise. Like [scale] it
 * is applied around the element's center.
 */
data class SnyggRotationValue(val degrees: Float) : SnyggValue {
    companion object : SnyggValueEncoder {
        override val spec = SnyggValueSpec {
            float(
                id = Angle,
                unit = DegUnit,
                numberPattern = """-?(?:0|[1-9][0-9]*)(?:[.][0-9]*)?|-?[.][0-9]+""".toRegex(),
            )
        }

        override fun defaultValue() = SnyggRotationValue(0f)

        override fun serialize(v: SnyggValue) = runCatching<String> {
            require(v is SnyggRotationValue)
            val map = snyggIdToValueMapOf(Angle to v.degrees)
            return@runCatching spec.pack(map)
        }

        override fun deserialize(v: String) = runCatching<SnyggValue> {
            val map = snyggIdToValueMapOf()
            spec.parse(v, map)
            return@runCatching SnyggRotationValue(map.getFloat(Angle))
        }
    }

    override fun encoder() = Companion
}

/**
 * How a value eases from its old to its new state over the transition duration. Mirrors the CSS timing
 * function keywords so a stylesheet reads the way people expect.
 */
data class SnyggEasingValue(val easing: Easing) : SnyggValue {
    companion object : SnyggEnumLikeValueEncoder<Easing>(
        serializationId = "easing",
        serializationMapping = mapOf(
            "linear" to LinearEasing,
            "ease" to FastOutSlowInEasing,
            "ease-in" to FastOutLinearInEasing,
            "ease-out" to LinearOutSlowInEasing,
            "ease-in-out" to FastOutSlowInEasing,
            // A gentle overshoot, for a press that springs back past its resting size.
            "bounce" to CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f),
        ),
        default = FastOutSlowInEasing,
        construct = { SnyggEasingValue(it) },
        destruct = { (it as SnyggEasingValue).easing },
    )

    override fun encoder() = Companion
}

/**
 * A signed dp offset, e.g. `"-2dp"`. Unlike a plain dp size this may be negative, because shifting an element
 * up or towards the start is just as useful as shifting it down or towards the end.
 */
data class SnyggOffsetValue(val dp: Dp) : SnyggValue {
    companion object : SnyggValueEncoder {
        override val spec = SnyggValueSpec {
            float(
                id = Offset,
                unit = DpUnit,
                numberPattern = """-?(?:0|[1-9][0-9]*)(?:[.][0-9]*)?|-?[.][0-9]+""".toRegex(),
            )
        }

        override fun defaultValue() = SnyggOffsetValue(0.dp)

        override fun serialize(v: SnyggValue) = runCatching<String> {
            require(v is SnyggOffsetValue)
            val map = snyggIdToValueMapOf(Offset to v.dp.value)
            return@runCatching spec.pack(map)
        }

        override fun deserialize(v: String) = runCatching<SnyggValue> {
            val map = snyggIdToValueMapOf()
            spec.parse(v, map)
            return@runCatching SnyggOffsetValue(map.getFloat(Offset).dp)
        }
    }

    override fun encoder() = Companion
}
