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

package dev.patrickgold.florisboard.ime.codetools

import dev.patrickgold.florisboard.ime.editor.CodeApps
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CalculatorTest : FunSpec({
    test("calculations before '=' are suggested") {
        Calculator.suggestionFor("total 12*4=") shouldBe "48"
        Calculator.suggestionFor("(2+3)×4 =") shouldBe "20"
        Calculator.suggestionFor("10÷4=") shouldBe "2.5"
        Calculator.suggestionFor("٣+٤=") shouldBe "7"
        Calculator.suggestionFor("2^10=") shouldBe "1024"
        Calculator.suggestionFor("200*15%=") shouldBe "30"
        Calculator.suggestionFor("12,500+500=") shouldBe "13000"
        Calculator.suggestionFor("0.1+0.2=") shouldBe "0.3"
        Calculator.suggestionFor("-3*-3=") shouldBe "9"
    }

    test("no suggestion without a calculation") {
        Calculator.suggestionFor("x = 5") shouldBe null
        Calculator.suggestionFor("42=") shouldBe null
        Calculator.suggestionFor("a == b") shouldBe null
        Calculator.suggestionFor("1/0=") shouldBe null
        Calculator.suggestionFor("(1+2=") shouldBe null
        Calculator.suggestionFor("hello") shouldBe null
    }

    test("code apps") {
        CodeApps.isCodeApp("com.termux") shouldBe true
        CodeApps.isCodeApp("com.foxdebug.acode") shouldBe true
        CodeApps.isCodeApp("com.whatsapp") shouldBe false
        CodeApps.isCodeApp(null) shouldBe false
    }
})
