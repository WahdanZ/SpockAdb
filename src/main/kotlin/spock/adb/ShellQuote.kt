package spock.adb

/**
 * POSIX-safe quoting for values interpolated into `adb shell` command lines.
 *
 * Commands are built as strings and handed to `IDevice.executeShellCommand`, which runs
 * them through the device's shell. Several of those strings interpolate values the user
 * typed — the deep link URI and the "input text" field — using hand-written quotes:
 *
 * ```
 * "input text '$p'"
 * "am start -a android.intent.action.VIEW -d \"$p\""
 * ```
 *
 * A value containing the matching quote character closes it early and everything after is
 * interpreted as shell syntax, so pasting a crafted deep link ran arbitrary commands on the
 * connected device with the shell user's privileges.
 *
 * Wrapping in single quotes suppresses every form of shell expansion. A single quote cannot
 * be escaped inside single quotes, so each one is emitted as `'\''` — close, escaped quote,
 * reopen.
 */
object ShellQuote {

    fun quote(value: String): String = buildString {
        append('\'')
        value.forEach { c ->
            if (c == '\'') append("'\\''") else append(c)
        }
        append('\'')
    }

    /**
     * Sanity-checks a value that should be a package name or activity component.
     *
     * [quote] is the security boundary — every interpolated value goes through it. This is
     * a separate, deliberately loose check used to turn obviously malformed device output
     * into a clear message instead of a confusing `am`/`pm` error. `$` is allowed, since
     * inner-class components legitimately contain it.
     */
    fun requireValidComponent(value: String, what: String): String {
        require(value.isNotBlank()) { "$what must not be blank" }
        require(value.none { it.isWhitespace() }) { "$what must not contain whitespace: '$value'" }
        require(value.none { it in SHELL_METACHARACTERS }) {
            "$what does not look like a package or component name: '$value'"
        }
        return value
    }

    private val SHELL_METACHARACTERS =
        charArrayOf(';', '&', '|', '<', '>', '(', ')', '`', '\\', '"', '\'', '\n', '\r').toSet()
}
