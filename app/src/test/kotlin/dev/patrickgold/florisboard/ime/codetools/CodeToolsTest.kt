package dev.patrickgold.florisboard.ime.codetools

import dev.patrickgold.florisboard.ime.clipboard.ClipContentKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class CodeToolsTest : FunSpec({
    test("case cycle") {
        CodeTools.cycleCase("user name") shouldBe "userName"
        CodeTools.cycleCase("userName") shouldBe "user_name"
        CodeTools.cycleCase("user_name") shouldBe "USER_NAME"
        CodeTools.cycleCase("USER_NAME") shouldBe "user-name"
        CodeTools.cycleCase("user-name") shouldBe "UserName"
        CodeTools.cycleCase("UserName") shouldBe "userName"
        CodeTools.cycleCase("parseHTTPResponse") shouldBe "parse_http_response"
    }

    test("base64 round trip including arabic") {
        val text = "مرحبا {\"a\":1}"
        CodeTools.base64Decode(CodeTools.base64Encode(text)) shouldBe text
        CodeTools.base64Decode("not base64!!") shouldBe null
    }

    test("url encoding") {
        CodeTools.urlEncode("a b&c=د") shouldBe "a+b%26c%3D%D8%AF"
        CodeTools.urlDecode("a+b%26c%3D%D8%AF") shouldBe "a b&c=د"
        CodeTools.urlDecode("%zz") shouldBe null
    }

    test("json pretty print and minify") {
        val pretty = CodeTools.formatJson("""{"a":1,"b":[true,null]}""")!!
        pretty.contains('\n') shouldBe true
        CodeTools.formatJson(pretty) shouldBe """{"a":1,"b":[true,null]}"""
        CodeTools.formatJson("{oops") shouldBe null
    }

    test("uuid and timestamp") {
        CodeTools.uuid().length shouldBe 36
        CodeTools.timestamp(Instant.parse("2026-11-16T10:15:30.123Z")) shouldBe "2026-11-16T10:15:30Z"
    }
})

class ClipContentKindTest : FunSpec({
    // All secrets below are fake, generated for the test
    test("secrets") {
        for (secret in listOf(
            "sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
            "ghp_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
            "AKIAABCDEFGHIJKLMNOP",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk",
            "password: hunter2hunter2",
            "Xk9#mP2\$vL7qR4nT8wZ1",
        )) {
            ClipContentKind.isSecret(secret) shouldBe true
        }
    }

    test("not secrets") {
        for (text in listOf(
            "hello world, this is a normal sentence",
            "https://github.com/Zarax34/florisboard",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", // sha256 hex
            "مرحبا بك في لوحة المفاتيح",
        )) {
            ClipContentKind.isSecret(text) shouldBe false
        }
    }

    test("kinds") {
        ClipContentKind.detect("me@example.com") shouldBe setOf(ClipContentKind.EMAIL)
        ClipContentKind.detect("see https://example.com/docs") shouldBe setOf(ClipContentKind.LINK)
        ClipContentKind.detect("12,500.75") shouldBe setOf(ClipContentKind.NUMBER)
        ClipContentKind.detect("٤٥٦") shouldBe setOf(ClipContentKind.NUMBER)
        ClipContentKind.detect("call +966 55 123 4567").contains(ClipContentKind.PHONE) shouldBe true
        ClipContentKind.detect("just text") shouldBe emptySet()
    }
    test("one-time codes") {
        ClipContentKind.extractOneTimeCode("Your verification code is 482913. Do not share it.") shouldBe "482913"
        ClipContentKind.extractOneTimeCode("G-735201 is your Google verification code.") shouldBe "735201"
        ClipContentKind.extractOneTimeCode("رمز التحقق الخاص بك هو 4821") shouldBe "4821"
        ClipContentKind.extractOneTimeCode("The meeting costs 1500 dollars") shouldBe null
        ClipContentKind.extractOneTimeCode("Your code: call +966501234567") shouldBe null
        ClipContentKind.extractOneTimeCode("Login total 12500.75") shouldBe null
    }
})
