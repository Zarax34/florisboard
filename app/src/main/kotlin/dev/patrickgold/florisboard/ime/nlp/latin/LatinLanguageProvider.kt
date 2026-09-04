/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import org.florisboard.lib.kotlin.guardedByLock
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * Default dictionary-backed NLP provider, used by every subtype unless it explicitly requests another provider.
 * Despite its historic name it is not limited to Latin-script languages: it loads a word-frequency dictionary for
 * the subtype's primary language (see [DICT_ASSET_BY_LANGUAGE]) and provides:
 *  - prefix-based word suggestions for the word currently being typed,
 *  - conservative autocorrect for that word (gated by the "correction__auto_correct_enabled" preference),
 *  - next-word prediction based on a small on-device bigram model learned from the user's own typing, and
 *  - dictionary + edit-distance based spell checking,
 * for every language it has a dictionary for. Languages without a bundled dictionary keep the previous no-op
 * behavior instead of producing bogus results.
 */
class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "org.florisboard.nlp.providers.latin"

        /** Maps a two-letter subtype language code to the dictionary asset providing suggestions for it. */
        private val DICT_ASSET_BY_LANGUAGE = mapOf(
            "en" to "ime/dict/data.json",
            "ar" to "ime/dict/ar.json",
        )

        /** Maximum edit distance for a dictionary word to be considered a typo correction candidate. */
        private const val MAX_SPELLING_EDIT_DISTANCE = 2

        /** Minimum length of a word before it is eligible for silent autocorrect-on-commit. */
        private const val MIN_AUTOCORRECT_WORD_LENGTH = 3

        /** Directory (relative to the app's files dir) the learned bigram data is persisted to. */
        private const val BIGRAM_DIR_NAME = "nlp"

        /** Upper bound on how many distinct "previous word" entries the bigram model keeps per language. */
        private const val BIGRAM_MAX_PREVIOUS_WORDS = 4000

        /** Upper bound on how many candidate next-words are kept per "previous word" entry. */
        private const val BIGRAM_MAX_NEXT_WORDS = 6

        /** Splits arbitrary text into word tokens, for both Latin- and Arabic-script (and beyond) text. */
        private val wordSplitRegex = Regex("[^\\p{L}\\p{M}]+")

        /**
         * Deletes all on-device data this provider has learned from the user's typing (currently: the next-word
         * bigram model). Suggestions/spelling from the bundled dictionaries are unaffected. Safe to call even if
         * nothing was ever learned.
         */
        fun clearLearnedData(context: Context) {
            File(context.filesDir, BIGRAM_DIR_NAME).deleteRecursively()
        }
    }

    private val appContext by context.appContext()
    private val prefs by FlorisPreferenceStore

    private val wordDataByLanguage = guardedByLock { mutableMapOf<String, Map<String, Int>>() }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())

    private val bigramsByLanguage = guardedByLock { mutableMapOf<String, MutableMap<String, MutableMap<String, Int>>>() }
    private val bigramSerializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Int.serializer()))
    private val lastLearnedPairByLanguage = guardedByLock { mutableMapOf<String, Pair<String, String>>() }

    override val providerId = ProviderId

    override suspend fun create() {
        // Nothing to set up eagerly, dictionaries/bigrams are loaded lazily per language in preload().
    }

    override suspend fun preload(subtype: Subtype) {
        val language = subtype.primaryLocale.language
        loadDictFor(language)
        loadBigramsFor(language)
    }

    /**
     * Returns the (possibly empty) frequency dictionary for [language], loading and caching it from assets on
     * first access. An empty map is returned - and cached - for any language without a bundled dictionary asset.
     */
    private suspend fun loadDictFor(language: String): Map<String, Int> = withContext(Dispatchers.IO) {
        wordDataByLanguage.withLock { cache ->
            cache.getOrPut(language) {
                val assetPath = DICT_ASSET_BY_LANGUAGE[language]
                if (assetPath == null) {
                    emptyMap()
                } else {
                    try {
                        val rawData = appContext.assets.readText(assetPath)
                        Json.decodeFromString(wordDataSerializer, rawData)
                    } catch (e: Exception) {
                        flogDebug { "Failed to load dictionary '$assetPath' for language '$language': $e" }
                        emptyMap()
                    }
                }
            }
        }
    }

    private fun bigramFile(language: String): File {
        return File(File(appContext.filesDir, BIGRAM_DIR_NAME), "bigrams_$language.json")
    }

    /** Returns the (possibly empty) learned bigram model for [language], loading it from disk on first access. */
    private suspend fun loadBigramsFor(language: String): Map<String, Map<String, Int>> = withContext(Dispatchers.IO) {
        bigramsByLanguage.withLock { cache ->
            val bigrams = cache.getOrPut(language) {
                try {
                    val file = bigramFile(language)
                    if (file.isFile) {
                        val raw = Json.decodeFromString(bigramSerializer, file.readText())
                        raw.mapValuesTo(mutableMapOf()) { it.value.toMutableMap() }
                    } else {
                        mutableMapOf()
                    }
                } catch (e: Exception) {
                    flogDebug { "Failed to load bigram data for language '$language': $e" }
                    mutableMapOf()
                }
            }
            bigrams.mapValues { it.value.toMap() }
        }
    }

    private suspend fun saveBigramsFor(language: String, bigrams: Map<String, Map<String, Int>>) = withContext(Dispatchers.IO) {
        try {
            val file = bigramFile(language)
            file.parentFile?.mkdirs()
            file.writeText(Json.encodeToString(bigramSerializer, bigrams))
        } catch (e: Exception) {
            flogDebug { "Failed to save bigram data for language '$language': $e" }
        }
    }

    /** Records that [next] followed [previous], reinforcing the on-device next-word model for [language]. */
    private suspend fun learnBigram(language: String, previous: String, next: String) {
        if (previous.isBlank() || next.isBlank() || previous == next) return
        val snapshot = bigramsByLanguage.withLock { cache ->
            val bigrams = cache.getOrPut(language) { mutableMapOf() }
            val isNewPreviousWord = !bigrams.containsKey(previous)
            if (isNewPreviousWord && bigrams.size >= BIGRAM_MAX_PREVIOUS_WORDS) {
                // Vocabulary cap reached: keep reinforcing what we already know, but stop growing further.
                return@withLock null
            }
            val nextCounts = bigrams.getOrPut(previous) { mutableMapOf() }
            nextCounts[next] = (nextCounts[next] ?: 0) + 1
            if (nextCounts.size > BIGRAM_MAX_NEXT_WORDS) {
                nextCounts.entries.minByOrNull { it.value }?.let { weakest ->
                    if (weakest.key != next) {
                        nextCounts.remove(weakest.key)
                    }
                }
            }
            bigrams.mapValues { it.value.toMap() }
        }
        if (snapshot != null) {
            saveBigramsFor(language, snapshot)
        }
    }

    private suspend fun predictNextWord(
        language: String,
        previousWord: String?,
        maxCandidateCount: Int,
    ): List<SuggestionCandidate> {
        if (previousWord.isNullOrBlank()) return emptyList()
        val nextCounts = loadBigramsFor(language)[previousWord] ?: return emptyList()
        return nextCounts.entries
            .sortedByDescending { it.value }
            .take(maxCandidateCount)
            .map { (word, _) ->
                WordSuggestionCandidate(
                    text = word,
                    confidence = 0.5,
                    isEligibleForAutoCommit = false,
                    sourceProvider = this,
                )
            }
    }

    /** Splits [text] into word tokens (letters/marks only) and returns the last [count] of them, in order. */
    private fun extractTrailingWords(text: String, count: Int): List<String> {
        val tokens = wordSplitRegex.split(text).filter { it.isNotBlank() }
        return if (tokens.size <= count) tokens else tokens.subList(tokens.size - count, tokens.size)
    }

    /** Finds dictionary words within [limit] edit distance of [normalized], closest and most frequent first. */
    private fun findCloseCorrections(
        dict: Map<String, Int>,
        normalized: String,
        limit: Int,
        maxResults: Int,
    ): List<Pair<String, Int>> {
        return dict.keys.asSequence()
            .filter { abs(it.length - normalized.length) <= limit }
            .mapNotNull { candidate ->
                val distance = boundedLevenshtein(normalized, candidate, limit)
                if (distance <= limit) candidate to distance else null
            }
            .sortedWith(compareBy({ it.second }, { -(dict[it.first] ?: 0) }))
            .take(maxResults)
            .toList()
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        // Kept for manual/debug testing purposes.
        when (word.lowercase()) {
            "typo" -> return SpellingResult.typo(arrayOf("typo1", "typo2", "typo3"))
            "gerror" -> return SpellingResult.grammarError(arrayOf("grammar1", "grammar2", "grammar3"))
        }

        val dict = loadDictFor(subtype.primaryLocale.language)
        if (dict.isEmpty() || word.isBlank()) {
            // No dictionary available for this language (yet) or nothing to check, don't claim anything.
            return SpellingResult.unspecified()
        }
        val normalized = word.lowercase(subtype.primaryLocale.base)
        if (dict.containsKey(normalized)) {
            return SpellingResult.validWord()
        }
        if (!normalized.all { it.isLetter() }) {
            // Numbers, URLs, emoji, etc. are not something we can/should spell check.
            return SpellingResult.validWord()
        }
        val candidates = findCloseCorrections(dict, normalized, MAX_SPELLING_EDIT_DISTANCE, maxSuggestionCount)
            .map { it.first }
        return if (candidates.isNotEmpty()) {
            // Word is unknown but we found close dictionary matches: flag it as a likely typo.
            SpellingResult.typo(candidates.toTypedArray())
        } else {
            // Word is unknown and we have no good correction to offer: don't flag proper nouns/loanwords/rare
            // words that simply aren't in our (frequency-limited) dictionary as wrong.
            SpellingResult.unspecified()
        }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        val language = subtype.primaryLocale.language
        val dict = loadDictFor(language)
        if (dict.isEmpty()) {
            return emptyList()
        }

        val query = content.composingText
        if (query.isBlank()) {
            // We're at a word boundary (e.g. right after a space): learn from the two words immediately
            // preceding the cursor, then predict what usually comes after the most recent one.
            val trailingWords = extractTrailingWords(content.textBeforeSelection, 2)
                .map { it.lowercase(subtype.primaryLocale.base) }
            if (!isPrivateSession && trailingWords.size == 2) {
                val pair = trailingWords[0] to trailingWords[1]
                val alreadyLearned = lastLearnedPairByLanguage.withLock { it.put(language, pair) } == pair
                if (!alreadyLearned) {
                    learnBigram(language, pair.first, pair.second)
                }
            }
            return predictNextWord(language, trailingWords.lastOrNull(), maxCandidateCount)
        }

        val normalizedQuery = query.lowercase(subtype.primaryLocale.base)
        val prefixMatches = dict.entries.asSequence()
            .filter { it.key.startsWith(normalizedQuery) }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(maxCandidateCount)
            .map { it.key to it.value }
            .toList()

        // Conservative autocorrect: only silently auto-commit when the typed word is unknown, long enough to not
        // be a plausible short/abbreviation, and has exactly one unambiguous close dictionary correction.
        var autoCommitWord: String? = null
        if (!isPrivateSession &&
            prefs.correction.autoCorrectEnabled.get() &&
            normalizedQuery.length >= MIN_AUTOCORRECT_WORD_LENGTH &&
            normalizedQuery.all { it.isLetter() } &&
            !dict.containsKey(normalizedQuery)
        ) {
            val corrections = findCloseCorrections(dict, normalizedQuery, MAX_SPELLING_EDIT_DISTANCE, 2)
            val best = corrections.getOrNull(0)
            val runnerUp = corrections.getOrNull(1)
            if (best != null && (runnerUp == null || best.second < runnerUp.second)) {
                autoCommitWord = best.first
            }
        }

        val candidates = buildList {
            val autoWord = autoCommitWord
            if (autoWord != null && prefixMatches.none { it.first == autoWord }) {
                add(autoWord to (dict[autoWord] ?: 0))
            }
            addAll(prefixMatches)
        }.distinctBy { it.first }.take(maxCandidateCount)

        return candidates.map { (word, freq) ->
            WordSuggestionCandidate(
                text = word,
                confidence = (freq / 255.0).coerceIn(0.0, 1.0),
                isEligibleForAutoCommit = word == autoCommitWord,
                sourceProvider = this,
            )
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // We can use flogDebug, flogInfo, flogWarning and flogError for debug logging, which is a wrapper for Logcat
        flogDebug { candidate.toString() }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        flogDebug { candidate.toString() }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return loadDictFor(subtype.primaryLocale.language).keys.toList()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return loadDictFor(subtype.primaryLocale.language).getOrDefault(word, 0) / 255.0
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }
}

/**
 * Computes the Levenshtein (edit) distance between [a] and [b], stopping early and returning [limit] + 1 as soon
 * as the distance is guaranteed to exceed [limit]. This keeps spell check correction lookups cheap even when
 * scanning a dictionary with tens of thousands of entries.
 */
private fun boundedLevenshtein(a: String, b: String, limit: Int): Int {
    val n = a.length
    val m = b.length
    if (abs(n - m) > limit) return limit + 1
    var prev = IntArray(m + 1) { it }
    var curr = IntArray(m + 1)
    for (i in 1..n) {
        curr[0] = i
        var rowMin = curr[0]
        for (j in 1..m) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = min(min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost)
            if (curr[j] < rowMin) rowMin = curr[j]
        }
        if (rowMin > limit) return limit + 1
        val tmp = prev
        prev = curr
        curr = tmp
    }
    return prev[m]
}
