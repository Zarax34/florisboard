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

/**
 * Recognizers (Vosk in particular) return lowercase text without punctuation. Dictation is made to read like typed
 * text: a sentence starts with a capital letter and a dictated sentence (a pause) ends with '.', or a question mark
 * when it starts with a question word. Short phrases (search terms, single words) get no punctuation.
 */
object DictationFormatter {
    private const val MIN_WORDS_FOR_PUNCTUATION = 3
    private val SentenceEnd = Regex("""(^|[.!?؟\n])\s*$""")
    private val EndsWithPunctuation = Regex("""[.!?؟,،;:…]$""")
    private val EnglishQuestionWords = setOf(
        "what", "why", "how", "who", "whom", "where", "when", "which", "is", "are", "am", "do", "does", "did",
        "can", "could", "would", "should", "will", "shall", "may", "have", "has",
    )
    private val ArabicQuestionWords = setOf("هل", "ماذا", "لماذا", "ليش", "كيف", "متى", "أين", "وين", "من", "ما", "كم", "أي", "شو", "ايش")

    fun format(text: String, textBefore: CharSequence): String {
        var result = text.trim()
        if (result.isEmpty()) return result
        if (SentenceEnd.containsMatchIn(textBefore)) {
            result = result.replaceFirstChar { it.titlecaseChar() }
        }
        val words = result.split(Regex("""\s+"""))
        if (words.size >= MIN_WORDS_FOR_PUNCTUATION && !EndsWithPunctuation.containsMatchIn(result)) {
            val first = words.first().lowercase()
            result += when {
                first in ArabicQuestionWords -> "؟"
                first in EnglishQuestionWords -> "?"
                else -> "."
            }
        }
        return result
    }
}
