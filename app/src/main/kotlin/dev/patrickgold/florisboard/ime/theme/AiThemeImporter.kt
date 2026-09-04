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

import android.content.Context
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import dev.patrickgold.florisboard.lib.ext.ExtensionDefaults
import dev.patrickgold.florisboard.lib.ext.ExtensionMaintainer
import dev.patrickgold.florisboard.lib.ext.ExtensionMeta
import org.florisboard.lib.kotlin.io.FsDir
import org.florisboard.lib.kotlin.io.subDir
import org.florisboard.lib.kotlin.io.subFile
import org.florisboard.lib.snygg.SnyggJsonConfiguration
import org.florisboard.lib.snygg.SnyggStylesheet

/**
 * Turns a stylesheet that an AI assistant produced - and that the user pasted into the app - into a real,
 * selectable theme, without the user having to deal with .flex files or the extension editor.
 */
object AiThemeImporter {
    /**
     * The parser is deliberately strict about the schema and about rules/properties/values it does not know,
     * so that a broken generation is reported to the user instead of silently producing a half-styled
     * keyboard.
     */
    private val PARSER_CONFIG = SnyggJsonConfiguration.of(
        ignoreMissingSchema = true,
        ignoreInvalidSchema = true,
    )

    /** Checks that [stylesheetJson] is a stylesheet this app can actually render. */
    fun validate(stylesheetJson: String): Result<SnyggStylesheet> {
        val trimmed = stylesheetJson.trim().removeMarkdownFence()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("The stylesheet is empty"))
        }
        return SnyggStylesheet.fromJson(trimmed, PARSER_CONFIG)
    }

    /**
     * Validates [stylesheetJson] and, if it is sound, installs it as a new theme named [label].
     *
     * @return the name of the installed theme component, which can be handed to the theme preferences to
     *  activate it right away.
     */
    fun install(
        context: Context,
        label: String,
        stylesheetJson: String,
        isNightTheme: Boolean,
    ): Result<ExtensionComponentName> = runCatching {
        validate(stylesheetJson).getOrThrow()
        val extensionManager by context.extensionManager()

        val timestamp = System.currentTimeMillis().toString()
        val themeId = "ai_theme_$timestamp"
        val extensionId = ExtensionDefaults.createLocalId("themes", timestamp)
        val stylesheetPath = ThemeExtensionComponent.defaultStylesheetPath(themeId)

        val workingDir: FsDir = context.cacheDir.subDir("ai-theme-$timestamp")
        try {
            workingDir.mkdirs()
            workingDir.subDir("stylesheets").mkdirs()
            workingDir.subFile(stylesheetPath).writeText(stylesheetJson.trim().removeMarkdownFence())

            val extension = ThemeExtension(
                meta = ExtensionMeta(
                    id = extensionId,
                    version = "1.0.0",
                    title = label.ifBlank { "AI theme" },
                    maintainers = listOf(ExtensionMaintainer(name = "Local")),
                    license = "(none specified)",
                ),
                dependencies = null,
                themes = listOf(
                    ThemeExtensionComponentImpl(
                        id = themeId,
                        label = label.ifBlank { "AI theme" },
                        authors = listOf("AI"),
                        isNightTheme = isNightTheme,
                        stylesheetPath = stylesheetPath,
                    )
                ),
            )
            extension.workingDir = workingDir
            extensionManager.import(extension)
            ExtensionComponentName(extensionId = extensionId, componentId = themeId)
        } finally {
            workingDir.deleteRecursively()
        }
    }

    /**
     * Models like to wrap their answer in a ```json fence even when told not to, so strip one if present
     * rather than failing the import over it.
     */
    private fun String.removeMarkdownFence(): String {
        if (!startsWith("```")) return this
        return substringAfter('\n').substringBeforeLast("```").trim()
    }
}
