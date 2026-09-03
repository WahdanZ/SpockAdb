package spock.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ShellQuoteTest {

    @Test
    fun `wraps plain values in single quotes`() {
        assertEquals("'hello'", ShellQuote.quote("hello"))
    }

    @Test
    fun `keeps spaces inside one argument`() {
        assertEquals("'hello world'", ShellQuote.quote("hello world"))
    }

    @Test
    fun `neutralises an embedded single quote instead of closing the string`() {
        // The attack on `input text '$p'`: a bare quote used to close the literal and let
        // everything after it be parsed as shell syntax.
        assertEquals("""'a'\''; rm -rf /sdcard; echo '\''b'""", ShellQuote.quote("a'; rm -rf /sdcard; echo 'b"))
    }

    @Test
    fun `neutralises double quotes, command substitution and separators`() {
        assertEquals("""'"; reboot; #'""", ShellQuote.quote("\"; reboot; #"))
        assertEquals("""'$(reboot)'""", ShellQuote.quote("\$(reboot)"))
        assertEquals("""'`reboot`'""", ShellQuote.quote("`reboot`"))
        assertEquals("""'a && reboot'""", ShellQuote.quote("a && reboot"))
    }

    @Test
    fun `quotes an empty value to a valid empty argument`() {
        assertEquals("''", ShellQuote.quote(""))
    }

    @Test
    fun `accepts ordinary package names and components`() {
        assertEquals("com.example.app", ShellQuote.requireValidComponent("com.example.app", "package"))
        assertEquals(
            "com.example.app/.MainActivity",
            ShellQuote.requireValidComponent("com.example.app/.MainActivity", "component"),
        )
    }

    @Test
    fun `rejects components carrying shell syntax`() {
        listOf("com.example; reboot", "com.example | sh", "com.example app", "com.example`x`", "")
            .forEach { malformed ->
                assertThrows(IllegalArgumentException::class.java) {
                    ShellQuote.requireValidComponent(malformed, "package")
                }
            }
    }

    @Test
    fun `accepts inner class components, which legitimately contain a dollar sign`() {
        // quote() is the security boundary, so the sanity check must not reject valid input.
        assertEquals(
            "com.example.app/.Outer\$Inner",
            ShellQuote.requireValidComponent("com.example.app/.Outer\$Inner", "component"),
        )
    }
}
