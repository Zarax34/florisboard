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

private const val Duration = "duration"
private const val MsUnit = "ms"
private const val Factor = "factor"

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
