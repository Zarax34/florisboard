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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

class VoskModelsTest : FunSpec({
    test("language from model file name") {
        VoskModels.languageFromFileName("vosk-model-small-en-us-0.15.zip") shouldBe "en"
        VoskModels.languageFromFileName("vosk-model-ar-mgb2-0.4.zip") shouldBe "ar"
        VoskModels.languageFromFileName("vosk-model-small-ar-tn-0.1-linto.zip") shouldBe "ar"
        VoskModels.languageFromFileName("my-model.zip") shouldBe null
    }

    test("import a model inside a top folder, then delete it") {
        val dir = Files.createTempDirectory("vosk").toFile()
        val models = VoskModels(dir)
        models.import("en", zipOf(
            "vosk-model-small-en-us-0.15/am/final.mdl" to "am",
            "vosk-model-small-en-us-0.15/conf/model.conf" to "conf",
        ).inputStream())
        models.installedLanguages() shouldBe listOf("en")
        File(models.modelDir("en"), "conf/model.conf").readText() shouldBe "conf"
        models.delete("en")
        models.installedLanguages() shouldBe emptyList()
        dir.deleteRecursively()
    }

    test("zips without a model or escaping the folder are rejected") {
        val dir = Files.createTempDirectory("vosk").toFile()
        val models = VoskModels(dir)
        shouldThrow<IOException> { models.import("en", zipOf("readme.txt" to "x").inputStream()) }
        shouldThrow<IOException> { models.import("en", zipOf("../../evil.txt" to "x").inputStream()) }
        File(dir.parentFile, "evil.txt").exists() shouldBe false
        models.installedLanguages() shouldBe emptyList()
        dir.deleteRecursively()
    }
})
