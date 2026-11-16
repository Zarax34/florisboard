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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the user actually typed, per language.
 *
 * Words are counted on every commit, but a word that is not in the base dictionary is only treated as a real word
 * once it was typed [LEARN_THRESHOLD] times. This way one-off typos are never memorized literally, while names,
 * slang or technical terms the user keeps typing become known after a few repetitions.
 */
@Serializable
class UserWordModel(
    private val unigrams: MutableMap<String, Int> = mutableMapOf(),
    private val bigrams: MutableMap<String, MutableMap<String, Int>> = mutableMapOf(),
    private val blocked: MutableSet<String> = mutableSetOf(),
) {
    companion object {
        const val LEARN_THRESHOLD = 3
        const val MAX_WORDS = 20_000
        private const val MAX_WORD_LENGTH = 48

        private val LearnableRegex = Regex("""^[\p{L}\p{M}'’\-]+$""")

        private val JsonConfig = Json { ignoreUnknownKeys = true }

        fun fromJson(json: String): UserWordModel = JsonConfig.decodeFromString(serializer(), json)

        /** Only plain words are learned, never emails, links, numbers or code. */
        fun isLearnable(word: String): Boolean {
            return word.length in 1..MAX_WORD_LENGTH && LearnableRegex.matches(word) && word.any { it.isLetter() }
        }
    }

    @Synchronized
    fun count(word: String): Int = unigrams[word] ?: 0

    @Synchronized
    fun isLearned(word: String): Boolean = count(word) >= LEARN_THRESHOLD

    @Synchronized
    fun isBlocked(word: String): Boolean = word in blocked

    @Synchronized
    fun bigramCount(previousWord: String, word: String): Int = bigrams[previousWord]?.get(word) ?: 0

    @Synchronized
    fun learn(word: String, previousWord: String?, weight: Int = 1) {
        if (!isLearnable(word)) return
        val newCount = count(word) + weight
        unigrams[word] = newCount
        if (newCount >= LEARN_THRESHOLD) blocked.remove(word)
        if (previousWord != null && isLearnable(previousWord)) {
            val next = bigrams.getOrPut(previousWord) { mutableMapOf() }
            next[word] = (next[word] ?: 0) + weight
        }
        if (unigrams.size > MAX_WORDS) prune()
    }

    /** User removed the word from the suggestions: forget it and never suggest it again until re-learned. */
    @Synchronized
    fun block(word: String) {
        unigrams.remove(word)
        bigrams.remove(word)
        bigrams.values.forEach { it.remove(word) }
        blocked.add(word)
    }

    /** Deleted from the learned-words screen: forget everything about the word, but it may be learned again. */
    @Synchronized
    fun forget(word: String) {
        unigrams.remove(word)
        bigrams.remove(word)
        bigrams.values.forEach { it.remove(word) }
    }

    @Synchronized
    fun clear() {
        unigrams.clear()
        bigrams.clear()
        blocked.clear()
    }

    /** Every word typed so far with how often, most frequent first. */
    @Synchronized
    fun wordCounts(): List<Pair<String, Int>> = unigrams.entries.sortedByDescending { it.value }.map { it.key to it.value }

    @Synchronized
    fun learnedWords(): List<String> = unigrams.filterValues { it >= LEARN_THRESHOLD }.keys.toList()

    @Synchronized
    fun nextWords(previousWord: String, limit: Int): List<Pair<String, Int>> {
        return bigrams[previousWord].orEmpty().entries
            .filter { it.key !in blocked }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
    }

    @Synchronized
    fun toJson(): String = JsonConfig.encodeToString(serializer(), this)

    // ponytail: frequency-only eviction, add last-used timestamps if useful rare words get evicted
    private fun prune() {
        val evict = unigrams.entries.sortedBy { it.value }.take(unigrams.size - MAX_WORDS * 9 / 10).map { it.key }.toSet()
        unigrams.keys.removeAll(evict)
        bigrams.keys.removeAll(evict)
        bigrams.values.forEach { it.keys.removeAll(evict) }
    }
}
