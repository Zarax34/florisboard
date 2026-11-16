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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class ShortcutPlaceholdersTest : FunSpec({
    val now = LocalDateTime.of(2026, 11, 16, 9, 5)

    test("placeholders are filled") {
        expandShortcut("Sent {date} at {time}", now) { null }.text shouldBe "Sent 2026-11-16 at 09:05"
        expandShortcut("التاريخ {تاريخ}", now) { null }.text shouldBe "التاريخ 2026-11-16"
        expandShortcut("Link: {clipboard}", now) { "https://example.com" }.text shouldBe "Link: https://example.com"
        expandShortcut("{clipboard}", now) { null }.text shouldBe ""
    }

    test("text without placeholders is untouched and the clipboard is not read") {
        var read = false
        expandShortcut("me@example.com {unknown}", now) { read = true; "x" }.text shouldBe "me@example.com {unknown}"
        read shouldBe false
    }
    test("snippets: cursor position, newline and tab") {
        val fn = expandShortcut("function {cursor}() {newline}{tab}return", now) { null }
        fn.text shouldBe "function () \n\treturn"
        fn.text.substring(0, fn.text.length - fn.cursorFromEnd) shouldBe "function "
        expandShortcut("<b>{مؤشر}</b>", now) { null } shouldBe ExpandedShortcut("<b></b>", cursorFromEnd = 4)
        expandShortcut("no cursor", now) { null }.cursorFromEnd shouldBe 0
    }
})
