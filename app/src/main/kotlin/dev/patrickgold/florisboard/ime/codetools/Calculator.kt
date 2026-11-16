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

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.pow

/**
 * The calculator in the suggestion bar: typing `12*4=` suggests `48`. Supports + - * / × ÷ ^ %, parentheses,
 * decimals and Arabic-Indic digits.
 */
object Calculator {
    // An expression right before a trailing '=': digits, operators, parentheses, separators, spaces
    private val TrailingExpression = Regex("""([\d٠-٩۰-۹.,٫+\-*/×÷^%() ]+)=$""")
    private val HasOperator = Regex("""\d\s*[+\-*/^%]|\)""")
    private const val MAX_EXPRESSION_LENGTH = 64

    /** The result to suggest for [textBeforeCursor], or null if it doesn't end with a calculation and '='. */
    fun suggestionFor(textBeforeCursor: CharSequence): String? {
        val expression = TrailingExpression.find(textBeforeCursor.takeLast(MAX_EXPRESSION_LENGTH + 1))
            ?.groupValues?.get(1)?.let(::normalizeDigits)?.trim() ?: return null
        if (!HasOperator.containsMatchIn(expression)) return null
        return evaluate(expression)?.let(::format)
    }

    fun evaluate(expression: String): Double? {
        return try {
            Parser(normalizeDigits(expression).replace(" ", "")).parse().takeIf { it.isFinite() }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun format(value: Double): String {
        return BigDecimal(value).round(MathContext(12)).stripTrailingZeros().toPlainString()
    }

    private fun normalizeDigits(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    '٫' -> '.'
                    '×' -> '*'
                    '÷' -> '/'
                    else -> c
                }
            )
        }
    }

    /** Recursive descent: expr = term (+|- term)*, term = power (*|/|% power)*, power = unary (^ power)? */
    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Double {
            val v = expr()
            require(i == s.length) { "Unexpected '${s[i]}'" }
            return v
        }

        private fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                v = if (s[i++] == '+') v + term() else v - term()
            }
            return v
        }

        private fun term(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                when (s[i++]) {
                    '*' -> v *= power()
                    '/' -> v /= power()
                    // "50%" alone means 0.5, "7%3" is the remainder
                    else -> v = if (i >= s.length || s[i] in "+-)") v / 100 else v % power()
                }
            }
            return v
        }

        private fun power(): Double {
            val base = unary()
            if (i < s.length && s[i] == '^') {
                i++
                return base.pow(power())
            }
            return base
        }

        private fun unary(): Double {
            if (i < s.length && (s[i] == '-' || s[i] == '+')) return if (s[i++] == '-') -unary() else unary()
            return atom()
        }

        private fun atom(): Double {
            require(i < s.length) { "Unexpected end" }
            if (s[i] == '(') {
                i++
                val v = expr()
                require(i < s.length && s[i] == ')') { "Missing ')'" }
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) i++
            require(i > start) { "Number expected" }
            // A comma is a thousands separator ("12,500") unless it is the only separator ("2,5")
            val raw = s.substring(start, i)
            val number = if ('.' !in raw && raw.count { it == ',' } == 1 && raw.substringAfter(',').length != 3) {
                raw.replace(',', '.')
            } else {
                raw.replace(",", "")
            }
            return number.toDoubleOrNull() ?: throw IllegalArgumentException("Bad number '$raw'")
        }
    }
}
