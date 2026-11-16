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

package dev.patrickgold.florisboard.ime.nlp.latin

/**
 * Arabizi (Arabic chat alphabet): `marhaba` -> مرحبا, `7abibi` -> حبيبي, `shukran` -> شكرا.
 * Every spelling the Latin letters could stand for is generated in [normalizeKey] space (short vowels may be
 * dropped, h may be ه or ح, ...) and only words of the Arabic dictionary are kept, most frequent first.
 */
object Arabizi {
    private const val MAX_INPUT_LENGTH = 14
    private const val MAX_VARIANTS = 6000
    private val InputRegex = Regex("""^[a-z2-9']+$""")
    /** Digits only Arabizi uses for letters; a word with them is almost surely Arabizi. */
    private val ArabiziDigits = Regex("[235679]")

    private val Digraphs = mapOf(
        "sh" to listOf("ش"), "ch" to listOf("ش"), "kh" to listOf("خ"), "gh" to listOf("غ"),
        "th" to listOf("ث", "ذ"), "dh" to listOf("ذ", "ض"),
        "aa" to listOf("ا"), "ee" to listOf("ي"), "ii" to listOf("ي"), "oo" to listOf("و"), "ou" to listOf("و"),
    )
    private val Letters = mapOf(
        'b' to listOf("ب"), 'p' to listOf("ب"), 't' to listOf("ت", "ط"), 'j' to listOf("ج"), 'g' to listOf("ج", "غ"),
        'h' to listOf("ه", "ح"), 'd' to listOf("د", "ض"), 'r' to listOf("ر"), 'z' to listOf("ز", "ظ"),
        's' to listOf("س", "ص"), 'f' to listOf("ف"), 'v' to listOf("ف"), 'q' to listOf("ق"), 'k' to listOf("ك", "ق"),
        'l' to listOf("ل"), 'm' to listOf("م"), 'n' to listOf("ن"), 'w' to listOf("و"), 'y' to listOf("ي"),
        'x' to listOf("كس"), 'c' to listOf("ك", "س"),
        '2' to listOf("ا", "ء"), '3' to listOf("ع"), '5' to listOf("خ"), '6' to listOf("ط"), '7' to listOf("ح"),
        '8' to listOf("ق", "غ"), '9' to listOf("ص", "ق"), '\'' to listOf("", "ء"),
    )

    /** Spellings of a vowel at the start, in the middle and at the end of a word. */
    private class VowelOptions(val start: List<String>, val middle: List<String>, val end: List<String>)

    private val Vowels = mapOf(
        'a' to VowelOptions(listOf("ا", "ع"), listOf("", "ا"), listOf("ا", "ه", "ي")),
        'e' to VowelOptions(listOf("ا", "ي"), listOf("", "ي"), listOf("ي", "ه", "")),
        'i' to VowelOptions(listOf("ا"), listOf("", "ي"), listOf("ي")),
        'o' to VowelOptions(listOf("ا", "و"), listOf("", "و"), listOf("و")),
        'u' to VowelOptions(listOf("ا", "و"), listOf("", "و"), listOf("و")),
    )

    fun isCandidate(typed: String): Boolean {
        val lower = typed.lowercase()
        return lower.length in 2..MAX_INPUT_LENGTH && InputRegex.matches(lower) && lower.any { it.isLetter() }
    }

    fun looksLikeArabizi(typed: String): Boolean = ArabiziDigits.containsMatchIn(typed)

    /**
     * Arabic words [typed] could be with their frequency, best first. [wordsForKey] returns the dictionary words
     * (with frequency) of a key.
     */
    fun transliterate(
        typed: String,
        maxCount: Int,
        wordsForKey: (String) -> List<Pair<String, Int>>,
    ): List<Pair<String, Int>> {
        if (!isCandidate(typed)) return emptyList()
        val found = HashMap<String, Int>()
        for (key in variants(typed.lowercase())) {
            for ((word, freq) in wordsForKey(key)) {
                if (freq > (found[word] ?: -1)) found[word] = freq
            }
        }
        return found.entries.sortedByDescending { it.value }.take(maxCount).map { it.key to it.value }
    }

    // ponytail: brute-force expansion capped at MAX_VARIANTS, a trie walk over the dictionary if longer words matter
    internal fun variants(input: String): Set<String> {
        var results = listOf("")
        for (options in tokenize(input)) {
            results = results.flatMap { prefix -> options.map { prefix + it } }
            if (results.size > MAX_VARIANTS) results = results.take(MAX_VARIANTS)
        }
        return results.filter { it.isNotEmpty() }.toSet()
    }

    private fun tokenize(input: String): List<List<String>> = buildList {
        var i = 0
        while (i < input.length) {
            val c = input[i]
            // Final "-an" is usually tanween: shukran -> شكراً
            if (i > 0 && i == input.length - 2 && input.endsWith("an")) {
                add(listOf("ا", "ان"))
                break
            }
            val digraph = input.substring(i, minOf(i + 2, input.length))
            if (digraph.length == 2 && digraph in Digraphs) {
                add(Digraphs.getValue(digraph))
                i += 2
                continue
            }
            when {
                // Doubled consonant (shadda) is written once
                i > 0 && c == input[i - 1] && c !in Vowels -> add(listOf(""))
                c in Vowels -> {
                    val options = Vowels.getValue(c)
                    add(when (i) {
                        0 -> options.start
                        input.length - 1 -> options.end
                        else -> options.middle
                    })
                }
                else -> add(Letters[c] ?: listOf(""))
            }
            i++
        }
    }
}
