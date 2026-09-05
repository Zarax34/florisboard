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
        private const val MIN_AUTOCORRECT_WORD_LENGTH = 4

        /** Words at or below this length may only ever be auto-corrected across a single edit. */
        private const val SHORT_WORD_LENGTH = 5

        /**
         * How much more frequent the best correction must be than the runner-up to be applied silently. Being
         * merely closer in edit distance is not enough: "cae" is one edit from both "case" and "care", and
         * guessing wrong there is worse than leaving the word alone.
         */
        private const val AUTOCORRECT_FREQUENCY_RATIO = 2.0

        /** Minimum query length before fuzzy (typo-tolerant) matches are mixed into the suggestions. */
        private const val MIN_FUZZY_QUERY_LENGTH = 3

        /** How strongly a word that usually follows the previous word is boosted while it is being typed. */
        private const val BIGRAM_BOOST = 3.0

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

    private val wordDataByLanguage = guardedByLock { mutableMapOf<String, Dictionary>() }
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
    private suspend fun loadDictFor(language: String): Dictionary = withContext(Dispatchers.IO) {
        wordDataByLanguage.withLock { cache ->
            cache.getOrPut(language) {
                val assetPath = DICT_ASSET_BY_LANGUAGE[language]
                if (assetPath == null) {
                    Dictionary.EMPTY
                } else {
                    try {
                        val rawData = appContext.assets.readText(assetPath)
                        Dictionary(Json.decodeFromString(wordDataSerializer, rawData))
                    } catch (e: Exception) {
                        flogDebug { "Failed to load dictionary '$assetPath' for language '$language': $e" }
                        Dictionary.EMPTY
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

    /**
     * Finds dictionary words within [limit] edit distance of [normalized], closest and most frequent first.
     *
     * Only the length buckets that could possibly be within [limit] are scanned, which is what keeps this
     * affordable on every keystroke against a dictionary of tens of thousands of words.
     */
    private fun findCloseCorrections(
        dict: Dictionary,
        normalized: String,
        limit: Int,
        maxResults: Int,
    ): List<Correction> {
        val results = mutableListOf<Correction>()
        for (length in (normalized.length - limit)..(normalized.length + limit)) {
            val bucket = dict.wordsByLength[length] ?: continue
            for (candidate in bucket) {
                val distance = boundedLevenshtein(normalized, candidate, limit)
                if (distance <= limit) {
                    results.add(
                        Correction(
                            word = candidate,
                            distance = distance,
                            frequency = dict.frequencies[candidate] ?: 0,
                            isRepeatSlip = isRepeatedLetterSlip(normalized, candidate),
                        )
                    )
                }
            }
        }
        // Equally close candidates are ordered by how likely the mistake is, then by how common the word is.
        results.sortWith(compareBy({ it.distance }, { !it.isRepeatSlip }, { -it.frequency }))
        return if (results.size <= maxResults) results else results.subList(0, maxResults)
    }

    /**
     * Ranks the dictionary words starting with [query]. Raw frequency alone is a poor ordering: it buries the
     * short completion the user is most likely reaching for under a longer, more common word. So the score
     * also favours candidates close in length to what has been typed, and boosts words that the learned
     * bigram model says usually follow [previousWord].
     */
    private fun rankPrefixMatches(
        dict: Dictionary,
        query: String,
        bigramCounts: Map<String, Int>,
        maxCandidateCount: Int,
    ): List<ScoredWord> {
        if (query.isEmpty()) return emptyList()
        val bucket = dict.wordsByFirstChar[query[0]] ?: return emptyList()
        val bigramTotal = bigramCounts.values.sum().coerceAtLeast(1)
        val scored = mutableListOf<ScoredWord>()
        for (word in bucket) {
            if (!word.startsWith(query)) continue
            val frequency = dict.frequencies[word] ?: 0
            val extraChars = word.length - query.length
            val lengthFactor = 1.0 / (1.0 + extraChars * 0.18)
            val bigramFactor = 1.0 + BIGRAM_BOOST * ((bigramCounts[word] ?: 0).toDouble() / bigramTotal)
            scored.add(ScoredWord(word, frequency, frequency * lengthFactor * bigramFactor))
        }
        scored.sortWith(compareByDescending<ScoredWord> { it.score }.thenBy { it.word })
        return if (scored.size <= maxCandidateCount) scored else scored.subList(0, maxCandidateCount)
    }

    /**
     * Decides whether [query] should be silently corrected to a dictionary word when the next separator is
     * typed, and to which word.
     *
     * The bar is deliberately high, because a wrong silent correction is far more annoying than a missing one:
     *  - the word must be unknown, and long enough that it is unlikely to be an abbreviation or an interjection,
     *  - it must not be the start of a real word, since the user is probably just not finished typing it,
     *  - short words may only be corrected across a single edit, and
     *  - the best correction must be clearly better than the next one, in distance or in frequency.
     */
    private fun chooseAutoCorrection(
        dict: Dictionary,
        query: String,
        corrections: List<Correction>,
    ): String? {
        if (query.length < MIN_AUTOCORRECT_WORD_LENGTH) return null
        if (!query.all { it.isLetter() }) return null
        if (dict.frequencies.containsKey(query)) return null
        if (dict.hasWordStartingWith(query)) return null
        val best = corrections.getOrNull(0) ?: return null
        val runnerUp = corrections.getOrNull(1) ?: return best.word
        // Clearly closer to what was typed than anything else.
        if (best.distance < runnerUp.distance) return best.word
        // Equally close, but only one is explained by a plainly accidental doubled letter.
        if (best.isRepeatSlip && !runnerUp.isRepeatSlip) return best.word
        // Otherwise only commit when one of them is decisively the more common word. When several real words
        // sit one edit away - which is the norm in Arabic, where they often differ only in the last letter -
        // guessing is worse than leaving the word alone.
        val isClearlyMoreFrequent = best.frequency >= runnerUp.frequency * AUTOCORRECT_FREQUENCY_RATIO
        return if (isClearlyMoreFrequent) best.word else null
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
        val dict = loadDictFor(subtype.primaryLocale.language)
        if (dict.frequencies.isEmpty() || word.isBlank()) {
            // No dictionary available for this language (yet) or nothing to check, don't claim anything.
            return SpellingResult.unspecified()
        }
        val normalized = word.lowercase(subtype.primaryLocale.base)
        if (dict.frequencies.containsKey(normalized)) {
            return SpellingResult.validWord()
        }
        if (!normalized.all { it.isLetter() }) {
            // Numbers, URLs, emoji, etc. are not something we can/should spell check.
            return SpellingResult.validWord()
        }
        val candidates = findCloseCorrections(dict, normalized, MAX_SPELLING_EDIT_DISTANCE, maxSuggestionCount)
            .map { it.word }
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
        if (dict.frequencies.isEmpty()) {
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

        // What usually follows the word before this one, used to bias the ranking towards words that actually
        // fit the sentence rather than merely towards common words.
        val previousWord = extractTrailingWords(content.textBeforeSelection, 1)
            .lastOrNull()
            ?.lowercase(subtype.primaryLocale.base)
        val bigramCounts = if (previousWord != null) {
            loadBigramsFor(language)[previousWord].orEmpty()
        } else {
            emptyMap()
        }

        val prefixMatches = rankPrefixMatches(dict, normalizedQuery, bigramCounts, maxCandidateCount)

        // A single mistyped letter kills every prefix match, so unless the prefix alone already fills the bar
        // we look for words that are simply close to what was typed. The same list decides the autocorrect,
        // so this scan happens at most once per keystroke.
        val isAutoCorrectEnabled = !isPrivateSession && prefs.correction.autoCorrectEnabled.get()
        val wantsFuzzy = normalizedQuery.length >= MIN_FUZZY_QUERY_LENGTH &&
            (isAutoCorrectEnabled || prefixMatches.size < maxCandidateCount)
        val fuzzyMatches = if (wantsFuzzy) {
            val limit = if (normalizedQuery.length <= SHORT_WORD_LENGTH) 1 else MAX_SPELLING_EDIT_DISTANCE
            findCloseCorrections(dict, normalizedQuery, limit, maxCandidateCount)
        } else {
            emptyList()
        }

        val autoCommitWord = if (isAutoCorrectEnabled) {
            chooseAutoCorrection(dict, normalizedQuery, fuzzyMatches)
        } else {
            null
        }

        val ordered = LinkedHashMap<String, Int>()
        autoCommitWord?.let { ordered[it] = dict.frequencies[it] ?: 0 }
        for (match in prefixMatches) {
            ordered.putIfAbsent(match.word, match.frequency)
        }
        for (match in fuzzyMatches) {
            ordered.putIfAbsent(match.word, match.frequency)
        }

        return ordered.entries.take(maxCandidateCount).map { (word, frequency) ->
            WordSuggestionCandidate(
                text = word,
                confidence = (frequency.toDouble() / dict.maxFrequency).coerceIn(0.0, 1.0),
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
        return loadDictFor(subtype.primaryLocale.language).frequencies.keys.toList()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        val dict = loadDictFor(subtype.primaryLocale.language)
        return (dict.frequencies[word] ?: 0).toDouble() / dict.maxFrequency
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }
}

/**
 * A word-frequency dictionary plus the indices the suggestion engine needs to stay fast.
 *
 * [wordsByLength] lets a fuzzy lookup scan only the buckets that could possibly be within the allowed edit
 * distance, and [sortedWords] makes "is anything in here starting with this?" a binary search rather than a
 * full scan - both of which run on every keystroke.
 */
private class Dictionary(val frequencies: Map<String, Int>) {
    val wordsByLength: Map<Int, List<String>> = frequencies.keys.groupBy { it.length }

    /** Words grouped by their first character, so a prefix lookup never scans the whole dictionary. */
    val wordsByFirstChar: Map<Char, List<String>> = frequencies.keys
        .filter { it.isNotEmpty() }
        .groupBy { it[0] }

    private val sortedWords: List<String> = frequencies.keys.sorted()

    /** The most common word's frequency, used to normalize confidences into 0..1. */
    val maxFrequency: Int = frequencies.values.maxOrNull() ?: 1

    /** Whether any word in this dictionary starts with [prefix]. */
    fun hasWordStartingWith(prefix: String): Boolean {
        if (prefix.isEmpty()) return sortedWords.isNotEmpty()
        var low = 0
        var high = sortedWords.size
        while (low < high) {
            val mid = (low + high) / 2
            if (sortedWords[mid] < prefix) low = mid + 1 else high = mid
        }
        return low < sortedWords.size && sortedWords[low].startsWith(prefix)
    }

    companion object {
        val EMPTY = Dictionary(emptyMap())
    }
}

/** A dictionary word offered as a correction for a mistyped one. */
private data class Correction(
    val word: String,
    val distance: Int,
    val frequency: Int,
    val isRepeatSlip: Boolean,
)

/**
 * Whether [typed] is [candidate] with one letter accidentally typed twice, e.g. "كتابب" for "كتاب". Holding a
 * key a moment too long is one of the most common typing slips there is, and unlike most single-letter
 * differences it is almost never a different real word - which makes it safe to correct silently.
 */
private fun isRepeatedLetterSlip(typed: String, candidate: String): Boolean {
    if (typed.length != candidate.length + 1) return false
    for (i in 1 until typed.length) {
        if (typed[i] != typed[i - 1]) continue
        if (typed.removeRange(i, i + 1) == candidate) return true
    }
    return false
}

/** A dictionary word ranked as a completion of what is currently being typed. */
private data class ScoredWord(val word: String, val frequency: Int, val score: Double)

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
