package spock.adb.uitree

/**
 * Effective default-display density, parsed from `wm density`. Read by [DisplayMetricsReader];
 * an unparseable answer is null, which leaves size checks explicitly unavailable.
 */
internal object DisplayDensity {
    fun parse(output: String): Int? {
        val override = density(output, "Override")
        return override ?: density(output, "Physical")
    }

    private fun density(output: String, kind: String): Int? =
        Regex("(?m)^\\s*$kind density: ([0-9]+)\\s*$").find(output)
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
}
