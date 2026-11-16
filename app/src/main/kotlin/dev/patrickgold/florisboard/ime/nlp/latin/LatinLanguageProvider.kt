/*
 * Copyright (C) 2022-2026 The FlorisBoard Contributors
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
import androidx.compose.ui.graphics.vector.ImageVector
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.codetools.Calculator
import dev.patrickgold.florisboard.ime.dictionary.expandShortcut
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.ShortcutCandidate
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import org.florisboard.lib.kotlin.guardedByLock
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Word prediction, auto-correction, next-word prediction and text-shortcut expansion for alphabet based languages.
 *
 * Base dictionaries live in `assets/ime/dict/<language>.json` (English: `data.json`). What the user types is learned
 * per language in `filesDir/nlp/user_words_<language>.json`, see [UserWordModel] for the learning rules.
 */
class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "org.florisboard.nlp.providers.latin"

        private const val SAVE_DELAY_MS = 5_000L
        private const val MAX_ARABIZI_SUGGESTIONS = 2
        /** An Arabizi reading at least this frequent blocks English auto-correction of the word. */
        private const val ARABIZI_COMMON_FREQ = 200
        private val NoAutoCorrectVariations = setOf(KeyVariation.EMAIL_ADDRESS, KeyVariation.PASSWORD, KeyVariation.URI)
        private val WordDataSerializer = MapSerializer(String.serializer(), Int.serializer())
        private val NextWordDataSerializer = MapSerializer(String.serializer(), WordDataSerializer)
        private val ModelFileRegex = Regex("""user_words_([a-z]+)\.json""")
    }

    private class LanguageData(val predictor: WordPredictor, val model: UserWordModel, val modelFile: File) {
        @Volatile var saveJob: Job? = null
    }

    /** The word the user typed, offered next to an auto-correction so it can be kept as-is. */
    private data class LiteralCandidate(
        override val text: CharSequence,
        override val sourceProvider: SuggestionProvider?,
    ) : SuggestionCandidate {
        override val secondaryText: CharSequence? = null
        override val confidence: Double = 0.0
        override val isEligibleForAutoCommit: Boolean = false
        override val isEligibleForUserRemoval: Boolean = false
        override val icon: ImageVector? = null
    }

    private val appContext by context.appContext()
    private val keyboardManager by context.keyboardManager()
    private val clipboardManager by context.clipboardManager()
    private val subtypeManager by context.subtypeManager()
    private val prefs by FlorisPreferenceStore
    private val dictionaryManager get() = DictionaryManager.default()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val languages = guardedByLock { mutableMapOf<String, LanguageData>() }

    /** Words whose auto-correction the user undid this session. They count towards learning as well. */
    private val keptLiterally = ConcurrentHashMap.newKeySet<String>()

    override val providerId = ProviderId

    override suspend fun create() {
        dictionaryManager.loadUserDictionariesIfNecessary()
    }

    override suspend fun preload(subtype: Subtype) {
        languageData(subtype)
    }

    private suspend fun languageData(subtype: Subtype): LanguageData = languageData(subtype.primaryLocale.language)

    private suspend fun languageData(language: String): LanguageData = withContext(Dispatchers.IO) {
        languages.withLock { it.getOrPut(language) { load(language) } }
    }

    private val modelDir get() = File(appContext.filesDir, "nlp")

    private fun modelFileOf(language: String) = File(modelDir, "user_words_$language.json")

    private fun load(language: String): LanguageData {
        val asset = if (language == "en") "ime/dict/data.json" else "ime/dict/$language.json"
        val words = try {
            Json.decodeFromString(WordDataSerializer, appContext.assets.readText(asset))
        } catch (e: IOException) {
            flogInfo { "No base dictionary for '$language' ($asset), using learned words only" }
            emptyMap()
        }
        val modelFile = modelFileOf(language)
        val model = if (modelFile.exists()) {
            try {
                UserWordModel.fromJson(modelFile.readText())
            } catch (e: SerializationException) {
                flogError { "Corrupt learned-words file $modelFile, starting fresh: $e" }
                modelFile.renameTo(File(modelFile.path + ".corrupt"))
                UserWordModel()
            }
        } else {
            UserWordModel()
        }
        val nextWords = try {
            Json.decodeFromString(NextWordDataSerializer, appContext.assets.readText("ime/dict/${language}_bigrams.json"))
        } catch (e: IOException) {
            emptyMap()
        }
        return LanguageData(WordPredictor(WordDictionary(words), model, NextWordTable(nextWords)), model, modelFile)
    }

    private fun save(data: LanguageData) {
        try {
            data.modelFile.parentFile?.mkdirs()
            val tmp = File(data.modelFile.path + ".tmp")
            tmp.writeText(data.model.toJson())
            if (!tmp.renameTo(data.modelFile)) flogError { "Could not replace ${data.modelFile}" }
        } catch (e: IOException) {
            flogError { "Failed to save learned words to ${data.modelFile}: $e" }
        }
    }

    private fun scheduleSave(data: LanguageData) {
        data.saveJob?.cancel()
        data.saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            save(data)
        }
    }

    private fun EditorContent.textBeforeComposing(): String {
        return if (localComposing.isValid) {
            text.substring(0, localComposing.start.coerceIn(0, text.length))
        } else {
            textBeforeSelection
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
        val predictor = languageData(subtype).predictor
        val locale = subtype.primaryLocale
        if (!UserWordModel.isLearnable(word) || predictor.exists(word.lowercase()) { dictionaryManager.spell(it, locale) }) {
            return SpellingResult.validWord()
        }
        val suggestions = predictor
            .predict(word, precedingWords.lastOrNull(), maxSuggestionCount, autoCorrect = false)
            .map { applyCase(word, it.word) }
        return SpellingResult.typo(suggestions.toTypedArray())
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        val predictor = languageData(subtype).predictor
        val locale = subtype.primaryLocale
        val typed = content.composingText
        val previousWord = lastWord(content.textBeforeComposing())

        // Text shortcuts from the user dictionary win over everything and expand on space
        val expansions = dictionaryManager.queryShortcutExpansions(typed, locale).map { expansion ->
            expandShortcut(expansion, LocalDateTime.now()) {
                clipboardManager.primaryClip?.takeIf { it.type == ItemType.TEXT && !it.isSensitive }?.text
            }
        }
        val calculation = Calculator.suggestionFor(content.textBeforeSelection)
        val arabizi = arabiziWords(subtype, typed)
        // A word that is clearly Arabizi must not be "corrected" into an English word
        val isArabizi = arabizi.isNotEmpty() &&
            (Arabizi.looksLikeArabizi(typed) || arabizi.first().second >= ARABIZI_COMMON_FREQ)
        val userWords = if (typed.isEmpty()) emptyList() else dictionaryManager.queryUserDictionary(typed, locale)
            .map { it.text.toString() }
            .filter { it.startsWith(typed, ignoreCase = true) && it != typed }
        val autoCorrect = expansions.isEmpty() && !isArabizi &&
            prefs.correction.autoCorrect.get() &&
            !keyboardManager.activeState.isCodeEditor &&
            keyboardManager.activeState.keyVariation !in NoAutoCorrectVariations
        val predictions = predictor.predict(typed, previousWord, maxCandidateCount, autoCorrect) { word ->
            word in keptLiterally || dictionaryManager.spell(word, locale)
        }

        val arabiziCandidates = arabizi.map { (word, _) ->
            WordSuggestionCandidate(word, secondaryText = typed, confidence = 1.0, sourceProvider = this)
        }

        return buildList<SuggestionCandidate> {
            if (calculation != null) {
                add(WordSuggestionCandidate(calculation, secondaryText = "=", confidence = 1.0, sourceProvider = this@LatinLanguageProvider))
            }
            expansions.forEachIndexed { i, expansion ->
                add(ShortcutCandidate(
                    text = expansion.text,
                    secondaryText = typed,
                    cursorFromEnd = expansion.cursorFromEnd,
                    isEligibleForAutoCommit = i == 0,
                    sourceProvider = this@LatinLanguageProvider,
                ))
            }
            if (Arabizi.looksLikeArabizi(typed)) addAll(arabiziCandidates)
            userWords.forEach { add(WordSuggestionCandidate(it, confidence = 1.0, sourceProvider = this@LatinLanguageProvider)) }
            for (prediction in predictions) {
                val word = applyCase(typed, prediction.word)
                if (none { it.text == word }) {
                    add(WordSuggestionCandidate(
                        text = word,
                        confidence = prediction.score.coerceIn(0.0, 1.0),
                        isEligibleForAutoCommit = prediction.isAutoCorrection,
                        sourceProvider = this@LatinLanguageProvider,
                    ))
                }
                if (prediction.isAutoCorrection) add(LiteralCandidate(typed, this@LatinLanguageProvider))
            }
            // Otherwise the Arabic reading comes right after the best English suggestion
            if (!Arabizi.looksLikeArabizi(typed)) addAll(minOf(size, 1), arabiziCandidates)
        }.distinctBy { it.text.toString() }.take(maxCandidateCount)
    }

    /** Arabic words for Latin [typed] text, if the user also has an Arabic keyboard (Arabizi, e.g. "marhaba"). */
    private suspend fun arabiziWords(subtype: Subtype, typed: String): List<Pair<String, Int>> {
        if (subtype.primaryLocale.language == "ar" || !Arabizi.isCandidate(typed)) return emptyList()
        if (subtypeManager.subtypes.none { it.primaryLocale.language == "ar" }) return emptyList()
        val arabic = languageData("ar").predictor
        return Arabizi.transliterate(typed, MAX_ARABIZI_SUGGESTIONS, arabic::wordsForKey)
    }

    /** Called for every word the user finishes (typed, tapped or auto-corrected). */
    suspend fun learn(subtype: Subtype, word: String, previousWord: String?) {
        val data = languageData(subtype)
        data.model.learn(word.lowercase(), previousWord?.lowercase())
        scheduleSave(data)
    }

    /** The user undid an auto-correction of [typed]: stop correcting it this session and count it once. */
    suspend fun keepLiterally(subtype: Subtype, typed: String) {
        keptLiterally.add(typed.lowercase())
        learn(subtype, typed, null)
    }

    /** Languages the user typed in so far (for the learned-words screen). */
    suspend fun learnedLanguages(): List<String> = withContext(Dispatchers.IO) {
        val onDisk = modelDir.listFiles().orEmpty().mapNotNull { ModelFileRegex.matchEntire(it.name)?.groupValues?.get(1) }
        (onDisk + languages.withLock { it.keys.toList() }).distinct().sorted()
    }

    /** Every word typed in [language] with its count, most frequent first. */
    suspend fun learnedWords(language: String): List<Pair<String, Int>> = languageData(language).model.wordCounts()

    suspend fun forgetWord(language: String, word: String) {
        val data = languageData(language)
        data.model.forget(word)
        scheduleSave(data)
    }

    suspend fun clearLearnedWords(language: String) {
        val data = languageData(language)
        data.model.clear()
        keptLiterally.clear()
        data.saveJob?.cancel()
        withContext(Dispatchers.IO) { save(data) }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        if (candidate is LiteralCandidate) keptLiterally.add(candidate.text.toString().lowercase())
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        // Auto-correction undo is handled through keepLiterally()
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        val data = languageData(subtype)
        data.model.block(candidate.text.toString().lowercase())
        scheduleSave(data)
        return true
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return languageData(subtype).predictor.words.toList()
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return languageData(subtype).predictor.frequency(word) / 255.0
    }

    override suspend fun destroy() {
        languages.withLock { it.values.forEach { data -> data.saveJob?.cancel(); save(data) } }
    }
}
