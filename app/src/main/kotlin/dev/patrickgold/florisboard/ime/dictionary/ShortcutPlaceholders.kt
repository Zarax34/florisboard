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

package dev.patrickgold.florisboard.ime.dictionary

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PlaceholderRegex = Regex("""\{(date|time|clipboard|newline|tab|تاريخ|وقت|حافظة|سطر)\}""")
private val CursorRegex = Regex("""\{(cursor|مؤشر)\}""")
private val DateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/** A filled text shortcut. [cursorFromEnd] > 0 means the cursor goes that many characters before the end. */
data class ExpandedShortcut(val text: String, val cursorFromEnd: Int = 0)

/**
 * Fills the placeholders a text shortcut may contain: `{date}`, `{time}`, `{clipboard}`, `{newline}`, `{tab}` and
 * `{cursor}` (Arabic aliases `{تاريخ}`, `{وقت}`, `{حافظة}`, `{سطر}`, `{مؤشر}`). [clipboard] is only queried if the text
 * uses it; null inserts nothing. Only the first `{cursor}` counts.
 */
fun expandShortcut(text: String, now: LocalDateTime, clipboard: () -> String?): ExpandedShortcut {
    if ('{' !in text) return ExpandedShortcut(text)
    val cursorMarker = '\uE000' // private use character, never part of real text
    val marked = CursorRegex.replaceFirst(text, cursorMarker.toString()).replace(CursorRegex, "")
    val filled = PlaceholderRegex.replace(marked) { match ->
        when (match.groupValues[1]) {
            "date", "تاريخ" -> now.format(DateFormat)
            "time", "وقت" -> now.format(TimeFormat)
            "newline", "سطر" -> "\n"
            "tab" -> "\t"
            else -> clipboard().orEmpty()
        }
    }
    val cursor = filled.indexOf(cursorMarker)
    if (cursor < 0) return ExpandedShortcut(filled)
    val result = filled.removeRange(cursor, cursor + 1)
    return ExpandedShortcut(result, cursorFromEnd = result.length - cursor)
}
