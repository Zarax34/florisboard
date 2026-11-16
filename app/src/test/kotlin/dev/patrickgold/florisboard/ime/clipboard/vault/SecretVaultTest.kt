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

package dev.patrickgold.florisboard.ime.clipboard.vault

import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain

class SecretVaultTest : FunSpec({
    // Fake secret, generated for the test
    val secret = "sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"

    test("only sensitive text clips go to the vault, and only when enabled") {
        SecretVault.shouldVault(enabled = true, ItemType.TEXT, isSensitive = true, secret) shouldBe true
        SecretVault.shouldVault(enabled = false, ItemType.TEXT, isSensitive = true, secret) shouldBe false
        SecretVault.shouldVault(enabled = true, ItemType.TEXT, isSensitive = false, "hello") shouldBe false
        SecretVault.shouldVault(enabled = true, ItemType.IMAGE, isSensitive = true, null) shouldBe false
        SecretVault.shouldVault(enabled = true, ItemType.TEXT, isSensitive = true, "  ") shouldBe false
    }

    test("dedupe hash is stable per salt, differs across salts and never contains the secret") {
        val a = SecretVault.newData()
        val b = SecretVault.newData()
        a.salt shouldNotBe b.salt
        val saltA = java.util.Base64.getDecoder().decode(a.salt)
        val saltB = java.util.Base64.getDecoder().decode(b.salt)
        SecretVault.dedupeHash(saltA, secret) shouldBe SecretVault.dedupeHash(saltA, secret)
        SecretVault.dedupeHash(saltA, secret) shouldNotBe SecretVault.dedupeHash(saltB, secret)
        SecretVault.dedupeHash(saltA, secret) shouldNotContain "sk-ant"
    }

    test("vault file round-trips and stores no plaintext") {
        val data = SecretVault.newData().copy(
            entries = listOf(VaultEntry("id1", 1000L, "hash", "wrapped", "iv", "ciphertext")),
        )
        val json = SecretVault.encode(data)
        SecretVault.decode(json) shouldBe data
        json shouldNotContain secret
    }
})
