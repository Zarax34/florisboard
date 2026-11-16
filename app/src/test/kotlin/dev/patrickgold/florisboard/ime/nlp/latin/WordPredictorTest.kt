package dev.patrickgold.florisboard.ime.nlp.latin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

private fun loadDict(name: String): WordDictionary {
    val json = File("src/main/assets/ime/dict/$name").readText()
    return WordDictionary(Json.decodeFromString(MapSerializer(String.serializer(), Int.serializer()), json))
}

private fun loadNextWords(name: String): NextWordTable {
    val json = File("src/main/assets/ime/dict/$name").readText()
    val serializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Int.serializer()))
    return NextWordTable(Json.decodeFromString(serializer, json))
}

class WordPredictorTest : FunSpec({
    val english = loadDict("data.json")
    val arabic = loadDict("ar.json")

    fun autoCorrection(dict: WordDictionary, typed: String, model: UserWordModel = UserWordModel()): String? {
        return WordPredictor(dict, model).predict(typed, null, 8, autoCorrect = true)
            .firstOrNull { it.isAutoCorrection }?.word
    }

    context("autocorrect fixes real typos") {
        for ((typed, expected) in listOf(
            "teh" to "the", "thier" to "their", "recieve" to "receive", "becuase" to "because",
            "taht" to "that", "jsut" to "just", "cna" to "can",
        )) {
            test("$typed -> $expected") { autoCorrection(english, typed) shouldBe expected }
        }
    }

    context("autocorrect leaves valid words alone") {
        for (word in listOf("form", "bat", "god", "fine", "the", "hello", "dont")) {
            test(word) { autoCorrection(english, word) shouldBe null }
        }
    }

    test("arabic common words are not corrected") {
        for (word in listOf("مرحبا", "شكرا", "أنا", "إلى")) {
            autoCorrection(arabic, word) shouldBe null
        }
    }

    test("arabic suggestions include the hamza form") {
        val words = WordPredictor(arabic, UserWordModel()).predict("انا", null, 8, autoCorrect = true).map { it.word }
        words.contains("أنا") shouldBe true
    }

    test("unknown word is corrected until typed LEARN_THRESHOLD times, then kept literally") {
        val model = UserWordModel()
        val typo = "helllo"
        autoCorrection(english, typo, model) shouldNotBe null
        repeat(UserWordModel.LEARN_THRESHOLD - 1) { model.learn(typo, null) }
        autoCorrection(english, typo, model) shouldNotBe null
        model.learn(typo, null)
        autoCorrection(english, typo, model) shouldBe null
    }

    test("completions rank frequent words first") {
        val top = WordPredictor(english, UserWordModel()).predict("bec", null, 3, autoCorrect = false).map { it.word }
        top.first() shouldBe "because"
    }

    test("next word comes from what the user typed, and only for real words") {
        val model = UserWordModel()
        model.learn("morning", "good")
        model.learn("xqzt", "good")
        val next = WordPredictor(english, model).predict("", "Good", 3, autoCorrect = true).map { it.word }
        next shouldBe listOf("morning")
    }

    test("emails, links and numbers are never learned") {
        val model = UserWordModel()
        listOf("me@mail.com", "https://x.io", "12345", "a_b").forEach { model.learn(it, null, weight = 10) }
        model.learnedWords() shouldBe emptyList()
    }

    test("model survives a json round trip") {
        val model = UserWordModel()
        repeat(3) { model.learn("florisboard", "use") }
        val restored = UserWordModel.fromJson(model.toJson())
        restored.isLearned("florisboard") shouldBe true
        restored.bigramCount("use", "florisboard") shouldBe 3
    }

    test("helpers") {
        editDistance("teh", "the", 1) shouldBe 1
        editDistance("abc", "xyz", 1) shouldBe 2
        normalizeKey("أنا") shouldBe normalizeKey("انا")
        lastWord("good morning ") shouldBe "morning"
        lastWord("end. ") shouldBe null
        applyCase("Teh", "the") shouldBe "The"
        applyCase("TEH", "the") shouldBe "THE"
    }
    test("neighboring key typos rank the neighbor first") {
        // 'v' sits next to 'c' but far from 'h'; "come" and "home" are equally frequent
        WordPredictor(english, UserWordModel()).predict("vome", null, 8, autoCorrect = true).first().word shouldBe "come"
        areKeyNeighbors('v', 'c') shouldBe true
        areKeyNeighbors('v', 'h') shouldBe false
        areKeyNeighbors('س', 'ش') shouldBe true
        typingDistance("vome", "come", 1.0) shouldBe NEIGHBOR_SUBSTITUTION_COST
        typingDistance("vome", "home", 1.0) shouldBe 1.0
    }

    test("forget and clear learned words") {
        val model = UserWordModel()
        repeat(3) { model.learn("flutter", "hello") }
        model.learn("kotlin", null)
        model.wordCounts() shouldBe listOf("flutter" to 3, "kotlin" to 1)
        model.forget("flutter")
        model.isLearned("flutter") shouldBe false
        model.bigramCount("hello", "flutter") shouldBe 0
        model.isBlocked("flutter") shouldBe false
        model.clear()
        model.wordCounts() shouldBe emptyList()
    }
    test("next words come from everyday language, not only from what the user typed") {
        val en = WordPredictor(english, UserWordModel(), loadNextWords("en_bigrams.json"))
        en.predict("", "thank", 3, autoCorrect = false).first().word shouldBe "you"
        en.predict("t", "want", 3, autoCorrect = false).first().word shouldBe "to"
        val ar = WordPredictor(arabic, UserWordModel(), loadNextWords("ar_bigrams.json"))
        ar.predict("", "صباح", 3, autoCorrect = false).first().word shouldBe "الخير"
        ar.predict("", "شكراً", 3, autoCorrect = false).map { it.word }.contains("لك") shouldBe true
    }

    test("what the user keeps typing next wins over the common next word") {
        val model = UserWordModel()
        repeat(5) { model.learn("guys", "thank") }
        WordPredictor(english, model, loadNextWords("en_bigrams.json"))
            .predict("", "thank", 3, autoCorrect = false).first().word shouldBe "guys"
    }
    test("arabizi finds the arabic word") {
        val ar = WordPredictor(arabic, UserWordModel())
        fun first(typed: String) = Arabizi.transliterate(typed, 3, ar::wordsForKey).firstOrNull()?.first
        first("marhaba") shouldBe "مرحباً"
        first("shukran") shouldBe "شكراً"
        first("7abibi") shouldBe "حبيبي"
        first("ana") shouldBe "أنا"
        Arabizi.isCandidate("hello123") shouldBe false
        Arabizi.isCandidate("x") shouldBe false
        Arabizi.looksLikeArabizi("3ala") shouldBe true
        Arabizi.looksLikeArabizi("hello") shouldBe false
    }
})
