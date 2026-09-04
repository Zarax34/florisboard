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
import kotlin.math.abs
import kotlin.math.min

/**
 * Default dictionary-backed NLP provider, used by every subtype unless it explicitly requests another provider.
 * Despite its historic name it is not limited to Latin-script languages: it loads a word-frequency dictionary for
 * the subtype's primary language (see [DICT_ASSET_BY_LANGUAGE]) and provides both prefix-based word suggestions
 * and dictionary + edit-distance based spell checking for every language it has a dictionary for. Languages
 * without a bundled dictionary keep the previous no-op behavior instead of producing bogus results.
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
    }

    private val appContext by context.appContext()

    private val wordDataByLanguage = guardedByLock { mutableMapOf<String, Map<String, Int>>() }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())

    override val providerId = ProviderId

    override suspend fun create() {
        // Nothing to set up eagerly, dictionaries are loaded lazily per language in preload()/loadDictFor().
    }

    override suspend fun preload(subtype: Subtype) {
        loadDictFor(subtype.primaryLocale.language)
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
        val candidates = dict.keys.asSequence()
            .filter { abs(it.length - normalized.length) <= MAX_SPELLING_EDIT_DISTANCE }
            .mapNotNull { candidate ->
                val distance = boundedLevenshtein(normalized, candidate, MAX_SPELLING_EDIT_DISTANCE)
                if (distance <= MAX_SPELLING_EDIT_DISTANCE) candidate to distance else null
            }
            .sortedWith(compareBy({ it.second }, { -(dict[it.first] ?: 0) }))
            .take(maxSuggestionCount)
            .map { it.first }
            .toList()
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
        val query = content.composingText
        if (query.isBlank()) {
            return emptyList()
        }
        val dict = loadDictFor(subtype.primaryLocale.language)
        if (dict.isEmpty()) {
            return emptyList()
        }
        val normalizedQuery = query.lowercase(subtype.primaryLocale.base)
        return dict.entries.asSequence()
            .filter { it.key.startsWith(normalizedQuery) }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(maxCandidateCount)
            .map { (word, freq) ->
                WordSuggestionCandidate(
                    text = word,
                    confidence = (freq / 255.0).coerceIn(0.0, 1.0),
                    // Never auto-commit: we only have frequency data, not enough context to be confident.
                    isEligibleForAutoCommit = false,
                    sourceProvider = this,
                )
            }
            .toList()
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
