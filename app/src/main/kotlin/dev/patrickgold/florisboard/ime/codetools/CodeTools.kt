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

package dev.patrickgold.florisboard.ime.codetools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * Text tools for people who write code, run on the selected text from the Smartbar quick actions.
 * Every transform returns `null` when the input is not valid for it.
 */
object CodeTools {
    enum class Case { PLAIN, CAMEL, SNAKE, CONSTANT, KEBAB, PASCAL }

    private val PrettyJson = Json { prettyPrint = true }
    private val CompactJson = Json
    private val WordBoundary = Regex("""[\s_\-]+|(?<=[\p{Ll}0-9])(?=\p{Lu})|(?<=\p{Lu})(?=\p{Lu}\p{Ll})""")

    fun detectCase(text: String): Case = when {
        text.any { it.isWhitespace() } -> Case.PLAIN
        '_' in text -> if (text.none { it.isLowerCase() }) Case.CONSTANT else Case.SNAKE
        '-' in text -> Case.KEBAB
        text.none { it.isLowerCase() } -> Case.CONSTANT
        text.first().isUpperCase() -> Case.PASCAL
        else -> Case.CAMEL
    }

    fun toCase(text: String, case: Case): String {
        val words = text.trim().split(WordBoundary).filter { it.isNotEmpty() }.map { it.lowercase() }
        fun capitalized() = words.map { w -> w.replaceFirstChar { it.uppercaseChar() } }
        return when (case) {
            Case.PLAIN -> words.joinToString(" ")
            Case.CAMEL -> capitalized().joinToString("").replaceFirstChar { it.lowercaseChar() }
            Case.SNAKE -> words.joinToString("_")
            Case.CONSTANT -> words.joinToString("_").uppercase()
            Case.KEBAB -> words.joinToString("-")
            Case.PASCAL -> capitalized().joinToString("")
        }
    }

    /** camelCase -> snake_case -> CONSTANT_CASE -> kebab-case -> PascalCase -> camelCase. Plain text starts at camelCase. */
    fun cycleCase(text: String): String {
        val order = listOf(Case.CAMEL, Case.SNAKE, Case.CONSTANT, Case.KEBAB, Case.PASCAL)
        var case = detectCase(text)
        repeat(order.size) {
            case = if (case == Case.PLAIN) Case.CAMEL else order[(order.indexOf(case) + 1) % order.size]
            val result = toCase(text, case)
            if (result != text) return result
        }
        return text
    }

    fun base64Encode(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    fun base64Decode(text: String): String? {
        val compact = text.filterNot { it.isWhitespace() }
        val bytes = try {
            Base64.getDecoder().decode(compact)
        } catch (e: IllegalArgumentException) {
            try {
                Base64.getUrlDecoder().decode(compact)
            } catch (e: IllegalArgumentException) {
                return null
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null // binary data, not text
        }
    }

    fun urlEncode(text: String): String = URLEncoder.encode(text, "UTF-8")

    fun urlDecode(text: String): String? = try {
        URLDecoder.decode(text, "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Pretty-prints single-line JSON and minifies multi-line JSON. */
    fun formatJson(text: String): String? {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return null
        }
        val json = if (text.trim().contains('\n')) CompactJson else PrettyJson
        return json.encodeToString(JsonElement.serializer(), element)
    }

    fun uuid(): String = UUID.randomUUID().toString()

    /** ISO-8601 UTC, e.g. `2026-11-16T10:15:30Z`. */
    fun timestamp(now: Instant = Instant.now()): String {
        return DateTimeFormatter.ISO_INSTANT.format(now.truncatedTo(ChronoUnit.SECONDS))
    }
}
