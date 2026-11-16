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

package dev.patrickgold.florisboard.ime.voice

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Offline speech models for Vosk, one folder per language in `filesDir/vosk/<language>`. The user downloads a model
 * zip from https://alphacephei.com/vosk/models and imports it in the settings; nothing is downloaded by the app.
 */
class VoskModels(filesDir: File) {
    companion object {
        private val ModelNameRegex = Regex("""vosk-model-(?:small-)?([a-z]{2,3})(?:[-_.]|$)""")
        private val LanguageRegex = Regex("[a-z]{2,3}")

        /** Language of a model from its file name, e.g. `vosk-model-small-en-us-0.15.zip` -> `en`. */
        fun languageFromFileName(fileName: String): String? {
            return ModelNameRegex.find(fileName.lowercase())?.groupValues?.get(1)
        }

        /** A Vosk model folder contains the acoustic model (`am/final.mdl`, or `final.mdl` in older models). */
        private fun isModelRoot(dir: File): Boolean = File(dir, "am/final.mdl").isFile || File(dir, "final.mdl").isFile
    }

    private val root = File(filesDir, "vosk")

    fun modelDir(language: String): File? = File(root, language).takeIf { isModelRoot(it) }

    fun installedLanguages(): List<String> {
        return root.listFiles().orEmpty().filter { it.isDirectory && isModelRoot(it) }.map { it.name }.sorted()
    }

    fun delete(language: String) {
        require(LanguageRegex.matches(language)) { "Invalid language '$language'" }
        File(root, language).deleteRecursively()
    }

    /**
     * Unpacks a model zip for [language], replacing an installed one. The zip may hold the model directly or inside
     * one top folder (as the official downloads do).
     *
     * @throws IOException if the zip is unreadable or holds no Vosk model.
     */
    fun import(language: String, zip: InputStream) {
        require(LanguageRegex.matches(language)) { "Invalid language '$language'" }
        root.mkdirs()
        val tmp = File(root, ".import-$language")
        tmp.deleteRecursively()
        try {
            unzip(zip, tmp)
            val modelRoot = tmp.walkTopDown().maxDepth(2).firstOrNull { it.isDirectory && isModelRoot(it) }
                ?: throw IOException("The zip does not contain a Vosk model")
            val target = File(root, language)
            target.deleteRecursively()
            if (!modelRoot.renameTo(target)) throw IOException("Could not move the model to $target")
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun unzip(input: InputStream, dest: File) {
        val destPath = dest.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val file = File(dest, entry.name)
                // Zip slip: never write outside the destination folder
                if (!file.canonicalPath.startsWith(destPath)) throw IOException("Invalid zip entry ${entry.name}")
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }
}
