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

package dev.patrickgold.florisboard.ime.clipboard

import dev.patrickgold.florisboard.lib.util.NetworkUtils
import kotlin.math.ln

/** What a text clip contains. A clip can contain several kinds (e.g. a message with a link and a phone number). */
enum class ClipContentKind {
    LINK,
    EMAIL,
    PHONE,
    NUMBER,
    SECRET;

    companion object {
        private val SecretPatterns = listOf(
            Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
            Regex("""\bsk-(?:ant-|proj-)?[A-Za-z0-9_\-]{20,}"""),               // OpenAI / Anthropic
            Regex("""\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}|\bgithub_pat_[A-Za-z0-9_]{40,}"""), // GitHub
            Regex("""\bAKIA[0-9A-Z]{16}\b"""),                                   // AWS access key
            Regex("""\bAIza[0-9A-Za-z_\-]{35}\b"""),                             // Google API key
            Regex("""\bxox[abprs]-[A-Za-z0-9\-]{10,}"""),                        // Slack
            Regex("""\b(?:sk|rk|pk)_(?:live|test)_[0-9A-Za-z]{16,}"""),          // Stripe
            Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}"""), // JWT
            Regex("""(?i)\b(?:password|passwd|pwd|secret|api[_\-]?key|access[_\-]?token|auth[_\-]?token|token)\s*[:=]\s*\S{6,}"""),
        )
        private val NumberRegex = Regex("""^[+\-]?[\d٠-٩]+(?:[.,][\d٠-٩]+)*$""")
        private val OtpKeywordRegex = Regex(
            """(?i)\b(?:code|otp|pin|passcode|verification|verify|security|login)\b|رمز|كود|التحقق|تحقق|التفعيل|الدخول""",
        )
        // 4-8 digits standing alone (not part of a longer number, amount or phone number)
        private val OtpCodeRegex = Regex("""(?<![\d٠-٩.,+])[\d٠-٩]{4,8}(?![\d٠-٩]|[.,][\d٠-٩])""")
        private const val MAX_OTP_MESSAGE_LENGTH = 500
        private const val MIN_SECRET_LENGTH = 20
        private const val MIN_SECRET_ENTROPY = 3.5

        fun detect(text: String): Set<ClipContentKind> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptySet()
            val emails = NetworkUtils.getEmailAddresses(trimmed).map { it.value }
            val isNumber = NumberRegex.matches(trimmed.replace(" ", ""))
            // Amounts like 12,500.75 also match the (loose) phone regex
            val isDecimalOrGrouped = isNumber && (',' in trimmed || '.' in trimmed)
            return buildSet {
                if (isSecret(trimmed)) add(SECRET)
                if (emails.isNotEmpty()) add(EMAIL)
                // The domain part of an email also matches the URL regex, don't count it as a link
                if (NetworkUtils.getUrls(trimmed).any { url -> emails.none { it.contains(url.value) } }) add(LINK)
                if (!isDecimalOrGrouped && NetworkUtils.getPhoneNumbers(trimmed).isNotEmpty()) add(PHONE)
                if (isNumber) add(NUMBER)
            }
        }

        /**
         * The one-time code in a copied message such as "Your verification code is 482913" or "رمز التحقق: 4821".
         * Requires a code keyword, so prices or dates in ordinary text are not mistaken for codes.
         */
        fun extractOneTimeCode(text: String): String? {
            if (text.length > MAX_OTP_MESSAGE_LENGTH || !OtpKeywordRegex.containsMatchIn(text)) return null
            return OtpCodeRegex.find(text)?.value
        }

        fun isSecret(text: String): Boolean {
            val trimmed = text.trim()
            return SecretPatterns.any { it.containsMatchIn(trimmed) } || looksLikeRandomToken(trimmed)
        }

        /**
         * A single long token mixing lowercase, uppercase and digits with high entropy, like a generated password
         * or API key. Hex hashes, plain words, links and emails don't qualify.
         */
        private fun looksLikeRandomToken(text: String): Boolean {
            if (text.length < MIN_SECRET_LENGTH || text.any { it.isWhitespace() }) return false
            if (NetworkUtils.isUrl(text) || NetworkUtils.isEmailAddress(text)) return false
            val hasLower = text.any { it in 'a'..'z' }
            val hasUpper = text.any { it in 'A'..'Z' }
            val hasDigit = text.any { it in '0'..'9' }
            if (!(hasLower && hasUpper && hasDigit)) return false
            return shannonEntropy(text) >= MIN_SECRET_ENTROPY
        }

        private fun shannonEntropy(text: String): Double {
            val n = text.length.toDouble()
            return text.groupingBy { it }.eachCount().values.sumOf { count ->
                val p = count / n
                -p * ln(p) / ln(2.0)
            }
        }
    }
}
