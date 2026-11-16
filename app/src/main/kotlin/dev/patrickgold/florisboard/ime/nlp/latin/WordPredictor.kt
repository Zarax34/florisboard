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

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Folds spelling variants that people commonly mix up into one lookup key: case, apostrophes, Arabic hamza/alef
 * forms, taa marbuta, alef maksura and diacritics. Two words with the same key are "the same word, spelled
 * differently" (e.g. `انا`/`أنا`, `dont`/`don't`).
 */
fun normalizeKey(word: String): String = buildString(word.length) {
    for (c in word.lowercase()) {
        when (c) {
            'أ', 'إ', 'آ', 'ٱ' -> append('ا')
            'ى', 'ئ' -> append('ي')
            'ة' -> append('ه')
            'ؤ' -> append('و')
            '\'', '’', 'ـ' -> Unit
            in 'ً'..'ٟ', 'ٰ' -> Unit
            else -> append(c)
        }
    }
}

/**
 * Optimal string alignment distance (Levenshtein + adjacent transposition), aborting early once the distance
 * exceeds [maxDistance]. Returns `maxDistance + 1` in that case.
 */
fun editDistance(a: String, b: String, maxDistance: Int): Int {
    return weightedEditDistance(a, b, maxDistance.toDouble(), transpositionCost = 1.0) { _, _ -> 1.0 }.toInt()
}

/** Hitting a key right next to the intended one is the most common typo, so it costs less than other edits. */
const val NEIGHBOR_SUBSTITUTION_COST = 0.5
/** Swapping two letters ("teh") is the other common slip when typing fast. */
const val TRANSPOSITION_COST = 0.5

// Rows of the QWERTY and Arabic layouts. Each row is shifted half a key to the right of the one above it.
private val KeyboardRows = listOf(
    listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
    listOf("ضصثقفغعهخحج", "شسيبلاتنمكط", "ذءؤرىةوزظد"),
)

private val KeyNeighbors: Map<Char, Set<Char>> = buildMap<Char, MutableSet<Char>> {
    for (layout in KeyboardRows) {
        val rows = layout.map(::normalizeKey)
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { i, c ->
                val neighbors = getOrPut(c) { mutableSetOf() }
                for ((dr, di) in listOf(0 to -1, 0 to 1, -1 to 0, -1 to 1, 1 to -1, 1 to 0)) {
                    rows.getOrNull(r + dr)?.getOrNull(i + di)?.takeIf { it != c }?.let(neighbors::add)
                }
            }
        }
    }
}

fun areKeyNeighbors(a: Char, b: Char): Boolean = KeyNeighbors[a]?.contains(b) == true

/**
 * [editDistance] where substituting a neighboring key costs [NEIGHBOR_SUBSTITUTION_COST] and swapping two letters
 * [TRANSPOSITION_COST]. Expects normalized keys.
 */
fun typingDistance(typed: String, word: String, maxDistance: Double): Double {
    return weightedEditDistance(typed, word, maxDistance, TRANSPOSITION_COST) { x, y ->
        if (areKeyNeighbors(x, y)) NEIGHBOR_SUBSTITUTION_COST else 1.0
    }
}

private inline fun weightedEditDistance(
    a: String,
    b: String,
    maxDistance: Double,
    transpositionCost: Double,
    substitutionCost: (Char, Char) -> Double,
): Double {
    if (abs(a.length - b.length) > maxDistance) return maxDistance + 1
    var prev2 = DoubleArray(b.length + 1)
    var prev = DoubleArray(b.length + 1) { it.toDouble() }
    var cur = DoubleArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i.toDouble()
        var rowMin = cur[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0.0 else substitutionCost(a[i - 1], b[j - 1])
            var v = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                v = min(v, prev2[j - 2] + transpositionCost)
            }
            cur[j] = v
            rowMin = min(rowMin, v)
        }
        if (rowMin > maxDistance) return maxDistance + 1
        val recycled = prev2
        prev2 = prev
        prev = cur
        cur = recycled
    }
    return prev[b.length]
}

private val TrailingWordRegex = Regex("""([\p{L}\p{M}'’]+)\s+$""")

/** The word right before the cursor, if it is only separated by whitespace (no sentence punctuation). */
fun lastWord(textBeforeCursor: CharSequence): String? {
    return TrailingWordRegex.find(textBeforeCursor.takeLast(64))?.groupValues?.get(1)
}

/** Returns [word] with the capitalization the user typed in [typed]. */
fun applyCase(typed: String, word: String): String {
    if (typed.isEmpty() || word.isEmpty() || !typed[0].isUpperCase()) return word
    return if (typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() }) {
        word.uppercase()
    } else {
        word.replaceFirstChar { it.uppercaseChar() }
    }
}

/** Base word list with frequencies on a 0..255 scale (the format of the json files in `assets/ime/dict`). */
class WordDictionary(private val frequencies: Map<String, Int>) {
    class Entry(val word: String, val key: String, val freq: Int)

    private val sortedByKey: List<Entry> = frequencies
        .map { (word, freq) -> Entry(word, normalizeKey(word), freq) }
        .sortedBy { it.key }
    private val byKeyLength: Map<Int, List<Entry>> = sortedByKey.groupBy { it.key.length }

    val words: Set<String> get() = frequencies.keys

    fun freq(word: String): Int = frequencies[word] ?: 0

    fun withPrefix(keyPrefix: String): List<Entry> {
        var lo = 0
        var hi = sortedByKey.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sortedByKey[mid].key < keyPrefix) lo = mid + 1 else hi = mid
        }
        var end = lo
        while (end < sortedByKey.size && sortedByKey[end].key.startsWith(keyPrefix)) end++
        return sortedByKey.subList(lo, end)
    }

    fun withKeyLength(lengths: IntRange): Sequence<Entry> {
        return lengths.asSequence().flatMap { byKeyLength[it].orEmpty() }
    }
}

/**
 * Words that commonly follow a word in everyday language, from the `<language>_bigrams.json` assets. Lookups are by
 * [normalizeKey], so `شكراً` finds the entries of `شكرا`.
 */
class NextWordTable(counts: Map<String, Map<String, Int>>) {
    companion object {
        val Empty = NextWordTable(emptyMap())
    }

    /** Next words per previous word, most common first, with a 0..1 log-scaled strength. */
    private val table: Map<String, List<Pair<String, Double>>> = counts.entries
        .groupBy({ normalizeKey(it.key) }, { it.value })
        .mapValues { (_, maps) ->
            val merged = maps.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
            val max = ln(1.0 + (merged.values.maxOrNull() ?: 0))
            merged.entries.sortedByDescending { it.value }.map { (word, count) -> word to ln(1.0 + count) / max }
        }

    fun next(previousWord: String): List<Pair<String, Double>> = table[normalizeKey(previousWord)].orEmpty()

    fun strength(previousWord: String, word: String): Double {
        return next(previousWord).firstOrNull { it.first == word }?.second ?: 0.0
    }
}

data class Prediction(
    val word: String,
    val score: Double,
    val isAutoCorrection: Boolean = false,
)

/**
 * Ranks completions, spelling corrections and next-word predictions from a base [dictionary] plus what the user
 * typed ([model]). All words are expected in lowercase; casing is applied by the caller.
 */
class WordPredictor(
    private val dictionary: WordDictionary,
    private val model: UserWordModel,
    private val nextWordTable: NextWordTable = NextWordTable.Empty,
) {
    companion object {
        /** Dictionary words at least this frequent are never auto-corrected. */
        const val WEAK_WORD_FREQ = 175
        /** A rare dictionary word is only corrected if the fix is this much more frequent. */
        const val MIN_FREQ_GAP = 60
        const val MIN_AUTOCORRECT_LENGTH = 3

        private const val EXACT_BONUS = 0.3
        private const val CORRECTION_PENALTY = 0.15
        private const val COMPLETION_LENGTH_PENALTY = 0.01
        private const val USER_WEIGHT = 0.1
        private const val BIGRAM_WEIGHT = 0.35
        private const val COMMON_NEXT_WORD_WEIGHT = 0.3

        private fun maxEditDistance(keyLength: Int): Int = when {
            keyLength < 2 -> 0
            keyLength < 6 -> 1
            else -> 2
        }

        private fun isWordChar(c: Char): Boolean {
            return c.isLetter() || c == '\'' || c == '’' || Character.getType(c) == Character.NON_SPACING_MARK.toInt()
        }
    }

    val words: Set<String> get() = dictionary.words + model.learnedWords()

    fun frequency(word: String): Int = max(dictionary.freq(word), if (model.isLearned(word)) WEAK_WORD_FREQ else 0)

    /** True if [word] is a real word: frequent dictionary word, user dictionary word or learned by repetition. */
    fun isKnown(word: String, isUserWord: (String) -> Boolean = { false }): Boolean {
        return dictionary.freq(word) >= WEAK_WORD_FREQ || model.isLearned(word) || isUserWord(word)
    }

    /** Dictionary words spelled like [key] (see [normalizeKey]) with their frequency. */
    fun wordsForKey(key: String): List<Pair<String, Int>> {
        return dictionary.withPrefix(key).filter { it.key == key && !model.isBlocked(it.word) }.map { it.word to it.freq }
    }

    /** True if [word] exists at all (used by the system spell checker, which should be lenient). */
    fun exists(word: String, isUserWord: (String) -> Boolean = { false }): Boolean {
        return dictionary.freq(word) > 0 || model.isLearned(word) || isUserWord(word)
    }

    fun predict(
        typed: String,
        previousWord: String?,
        maxCount: Int,
        autoCorrect: Boolean,
        isUserWord: (String) -> Boolean = { false },
    ): List<Prediction> {
        val prev = previousWord?.lowercase()
        if (typed.isEmpty()) return nextWords(prev, maxCount)

        val lower = typed.lowercase()
        val key = normalizeKey(lower)
        if (key.isEmpty()) return emptyList()

        val scores = HashMap<String, Double>()
        val corrections = HashSet<String>()
        fun offer(word: String, score: Double) {
            if (model.isBlocked(word)) return
            if (score > (scores[word] ?: Double.NEGATIVE_INFINITY)) scores[word] = score
        }

        val learned = model.learnedWords().map { WordDictionary.Entry(it, normalizeKey(it), dictionary.freq(it)) }

        // Completions, including the typed word itself and its spelling variants (same key)
        for (e in dictionary.withPrefix(key) + learned.filter { it.key.startsWith(key) }) {
            val bonus = if (e.key.length == key.length) {
                EXACT_BONUS
            } else {
                -COMPLETION_LENGTH_PENALTY * (e.key.length - key.length)
            }
            offer(e.word, baseScore(e.word, e.freq, prev) + bonus)
            if (e.key == key && e.word != lower) corrections.add(e.word)
        }

        // Spelling corrections
        val maxDistance = maxEditDistance(key.length)
        if (maxDistance > 0) {
            val lengths = (key.length - maxDistance)..(key.length + maxDistance)
            for (e in dictionary.withKeyLength(lengths) + learned.asSequence().filter { it.key.length in lengths }) {
                if (e.key == key) continue
                val distance = typingDistance(key, e.key, maxDistance.toDouble())
                if (distance > maxDistance) continue
                corrections.add(e.word)
                offer(e.word, baseScore(e.word, e.freq, prev) - CORRECTION_PENALTY * distance)
            }
        }

        val autoCorrection = if (autoCorrect) pickAutoCorrection(lower, key, corrections, scores, isUserWord) else null
        val ranked = scores.entries
            .filter { it.key != autoCorrection }
            .sortedByDescending { it.value }
            .map { Prediction(it.key, it.value) }
        return buildList {
            if (autoCorrection != null) {
                add(Prediction(autoCorrection, scores.getValue(autoCorrection), isAutoCorrection = true))
            }
            addAll(ranked)
        }.take(maxCount)
    }

    private fun pickAutoCorrection(
        lower: String,
        key: String,
        corrections: Set<String>,
        scores: Map<String, Double>,
        isUserWord: (String) -> Boolean,
    ): String? {
        if (key.length < MIN_AUTOCORRECT_LENGTH || !lower.all(::isWordChar)) return null
        if (isKnown(lower, isUserWord)) return null
        val target = corrections.maxByOrNull { scores[it] ?: Double.NEGATIVE_INFINITY } ?: return null
        val typedFreq = dictionary.freq(lower)
        return target.takeIf { typedFreq == 0 || frequency(target) - typedFreq >= MIN_FREQ_GAP }
    }

    /** After a space: what the user usually types next, merged with what commonly follows in the language. */
    private fun nextWords(prev: String?, maxCount: Int): List<Prediction> {
        if (prev == null) return emptyList()
        val candidates = LinkedHashSet<String>()
        model.nextWords(prev, maxCount * 2).mapTo(candidates) { it.first }
        nextWordTable.next(prev).mapTo(candidates) { it.first }
        return candidates
            .filter { !model.isBlocked(it) && (dictionary.freq(it) > 0 || model.isLearned(it)) }
            .map { Prediction(it, baseScore(it, dictionary.freq(it), prev)) }
            .sortedByDescending { it.score }
            .take(maxCount)
    }

    private fun baseScore(word: String, freq: Int, prev: String?): Double {
        var score = freq / 255.0
        val count = model.count(word)
        if (count > 0) score += USER_WEIGHT * ln(1.0 + count)
        if (prev != null) {
            val bigram = model.bigramCount(prev, word)
            if (bigram > 0) score += BIGRAM_WEIGHT * ln(1.0 + bigram)
            score += COMMON_NEXT_WORD_WEIGHT * nextWordTable.strength(prev, word)
        }
        return score
    }
}
