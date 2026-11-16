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

package dev.patrickgold.florisboard.ime.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DictationFormatterTest : FunSpec({
    test("sentences get a capital and an end mark") {
        DictationFormatter.format("i will be there soon", "") shouldBe "I will be there soon."
        DictationFormatter.format("how are you doing", "Hi. ") shouldBe "How are you doing?"
        DictationFormatter.format("هل انت في البيت", "") shouldBe "هل انت في البيت؟"
        DictationFormatter.format("انا في الطريق الان", "") shouldBe "انا في الطريق الان."
    }

    test("continuing a sentence keeps lowercase, short phrases stay as they are") {
        DictationFormatter.format("and then we left early", "We ate") shouldBe "and then we left early."
        DictationFormatter.format("pizza near me", "") shouldBe "Pizza near me."
        DictationFormatter.format("weather", "") shouldBe "Weather"
        DictationFormatter.format("ok thanks", "") shouldBe "Ok thanks"
        DictationFormatter.format("see you there.", "") shouldBe "See you there."
    }
})
