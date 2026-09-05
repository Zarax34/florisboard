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

package dev.patrickgold.florisboard.ime.theme

/**
 * Builds the briefing that a user hands to an AI assistant so it can write a complete FlorisBoard theme.
 *
 * The element list is derived from [FlorisImeUi] rather than hardcoded, so the prompt cannot drift away from
 * what the app actually styles as elements are added or renamed.
 */
object AiThemePrompt {
    /** The example is deliberately small but valid, so the model has a concrete shape to imitate. */
    private val EXAMPLE = """
        {
          "${'$'}schema": "https://schemas.florisboard.org/snygg/v2/stylesheet",
          "@defines": {
            "--primary": "#7c4dff",
            "--background": "#12121a",
            "--surface": "#232336",
            "--on-surface": "#ffffff",
            "--shape": "rounded-corner(14dp, 14dp, 14dp, 14dp)"
          },
          "window": { "background": "var(--background)", "foreground": "var(--on-surface)" },
          "key": {
            "background": "var(--surface)",
            "foreground": "var(--on-surface)",
            "font-size": "22sp",
            "shape": "var(--shape)",
            "shadow-elevation": "3dp",
            "transition-duration": "100ms",
            "scale": "1"
          },
          "key:pressed": { "background": "var(--primary)", "scale": "0.94" },
          "key[code=10]": { "background": "var(--primary)", "foreground": "#ffffff" },
          "smartbar-candidate-word": { "background": "transparent", "foreground": "var(--on-surface)" }
        }
    """.trimIndent()

    /**
     * Returns the full prompt. [description] is whatever the user typed as their design brief; when it is
     * blank a placeholder line is used instead so the copied prompt is still self-explanatory.
     */
    fun build(description: String): String {
        val elements = FlorisImeUi.entries.joinToString(", ") { it.elementName }
        val brief = description.trim().ifBlank { "<describe the keyboard design you want here>" }
        return """
            You are writing a theme for FlorisBoard, an open-source Android keyboard. Themes are written in
            Snygg, a CSS-like stylesheet format serialized as a single JSON object.

            # Output rules
            - Reply with ONE JSON object and nothing else. No markdown fences, no comments, no explanation.
            - The first key must be "${'$'}schema" with the exact value
              "https://schemas.florisboard.org/snygg/v2/stylesheet".
            - JSON has no comments, so do not add any. Every value is a STRING, including sizes and numbers.
            - Only use the element names, properties and value syntaxes listed below. Anything else is
              rejected by the app when the theme is imported.

            # Structure
            - "@defines" is an optional first block of reusable variables. Variable names must start with two
              dashes, e.g. "--primary". Reference them anywhere with "var(--primary)".
            - Every other top-level key is a rule that selects part of the keyboard UI:
              - "key"                  -> all keys
              - "key:pressed"          -> keys while held. Selectors: :pressed, :focus, :hover, :disabled
              - "key[code=10]"         -> a specific key by key code
              - "key[code=-7,-11]"     -> several key codes at once
              - "key[shiftstate=`caps_lock`]" -> an attribute whose value is text uses backticks
            - Rules are merged, not replaced: "key" applies to every key and a more specific rule adds to it.
              So put the shared look in "key" and only the differences in the specific rules.

            # Useful key codes
            10 = enter, 32 = space, -7 = backspace, -11 = shift, -201 = switch to letters,
            -202 = switch to symbols, -204/-205 = numeric layouts, -227 = language switch, -233 = microphone.

            # Properties
            - background, foreground, border-color, shadow-color: a color.
            - background-image: "url(relative/path.png)", with content-scale: crop | fit | fill-bounds | inside | none
            - border-width, shadow-elevation: a dp size, e.g. "2dp".
            - font-size, letter-spacing, line-height: an sp size, e.g. "22sp".
            - font-family: "system" | "sans-serif" | "serif" | "monospace" | "cursive".
            - font-style: "normal" | "italic".
            - font-weight: "thin" | "extra-light" | "light" | "normal" | "medium" | "semi-bold" | "bold" |
              "extra-bold" | "black", or a number as a string such as "700".
            - margin, padding: 1, 2 or 4 dp values, e.g. "8dp" or "4dp 8dp" or "4dp 8dp 4dp 8dp".
            - shape: "rectangle()" | "circle()" | "rounded-corner(8dp, 8dp, 8dp, 8dp)" |
              "rounded-corner(20%, 20%, 20%, 20%)" | "cut-corner(6dp, 6dp, 6dp, 6dp)" | "cut-corner(10%, ...)".
              The four corners are top-start, top-end, bottom-end, bottom-start.
            - clip: "yes" | "no".
            - scale: a size multiplier as a string, e.g. "0.94". 1 is the natural size.
            - transition-duration: how long a state change takes, e.g. "120ms". 0 or absent means instant.
            - text-align: "start" | "center" | "end" | "left" | "right" | "justify".
            - text-decoration-line: "none" | "underline" | "line-through".
            - text-max-lines: a whole number as a string, e.g. "1". text-overflow: "clip" | "ellipsis" | "visible".

            # Animation
            An element animates when it declares a non-zero "transition-duration". The properties that then
            ease into their new value instead of snapping are: background, foreground, border-color,
            shadow-color, border-width, shadow-elevation and scale. Because a ":pressed" rule is just another
            state of the same element, this is how you make a key react to a touch.

            To make keys dip and spring back when pressed, put "transition-duration" AND "scale": "1" on the
            base rule, and the smaller scale on the pressed rule:
              "key": { "transition-duration": "100ms", "scale": "1", ... }
              "key:pressed": { "scale": "0.94", "background": "..." }
            The "scale": "1" on the base rule matters: without it the key snaps back instead of animating
            back. Keep durations between 60ms and 250ms - anything slower feels laggy while typing, and
            keep pressed scale between 0.85 and 1.05.

            There is no keyframe, easing-curve, rotation or translation support. Do not invent properties
            like "animation", "transform" or "transition-timing-function" - they are rejected on import.

            # Colors
            "#rrggbb", "#rrggbbaa", "rgb(124, 77, 255)", "rgba(124, 77, 255, 0.5)", "transparent",
            or "var(--some-variable)".

            # Elements you may style
            {{ELEMENTS}}

            # What the theme must cover
            Always style at least: window, key, key:pressed, key[code=10], key[code=32], key-hint,
            key-popup-box, key-popup-element, key-popup-element:focus, smartbar,
            smartbar-shared-actions-toggle, smartbar-extended-actions-toggle, smartbar-action-key,
            smartbar-candidate-word, smartbar-candidate-word:pressed, smartbar-candidate-spacer, media,
            media-emoji-key, media-bottom-row, media-bottom-row-button, one-handed-panel,
            one-handed-panel-button and the clipboard-* elements. Keep contrast high enough to read the
            letters, and make sure every var() you reference is defined in "@defines".

            # Example of a valid (but minimal) stylesheet
            {{EXAMPLE}}

            # The design I want
            {{BRIEF}}

            Now output the complete stylesheet JSON.
        """.trimIndent()
            // Substituted after trimIndent(), because the multi-line example and brief would otherwise
            // defeat the common-indent calculation and leave the whole prompt indented.
            .replace("{{ELEMENTS}}", elements)
            .replace("{{EXAMPLE}}", EXAMPLE)
            .replace("{{BRIEF}}", brief)
    }
}
