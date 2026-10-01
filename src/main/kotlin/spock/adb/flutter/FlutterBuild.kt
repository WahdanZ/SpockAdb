package spock.adb.flutter

import spock.adb.ShellQuote

/** How a Flutter app was built, which decides what can be inspected in it. */
enum class FlutterBuild(val label: String) {
    /** JIT: `flutter_assets/kernel_blob.bin`, no `libapp.so`. The VM Service and the inspector are there. */
    DEBUG("debug"),

    /**
     * AOT and debuggable (Flutter's profile build type starts from debug). VM Service, no inspector.
     *
     * Told from [RELEASE] by the package's `DEBUGGABLE` flag alone — the AOT snapshot looks the
     * same in both — so a release build made debuggable, `isDebuggable = true` on the release
     * build type, reads as profile.
     */
    PROFILE("profile"),

    /** AOT and not debuggable. */
    RELEASE("release"),
    ;

    companion object {
        /**
         * Lists the Flutter files inside every APK of [packageName], including split APKs, where
         * the native libraries of an App Bundle install live. `unzip` ships with Android 10 and
         * later; on older devices the listing is empty and the app reads as not Flutter.
         */
        fun listingCommand(packageName: String): String {
            val quoted = ShellQuote.quote(packageName)
            return "for a in \$(pm path $quoted | sed 's/^package://'); do unzip -l \"\$a\" 2>/dev/null; done | " +
                "grep -E '$FLUTTER_FILES'"
        }

        /**
         * Null when [listing] has no Flutter engine: a native app, or a device without `unzip`.
         * Profile and release differ only in [debuggable]; see [PROFILE].
         */
        fun of(listing: String, debuggable: Boolean): FlutterBuild? {
            if (!ENGINE.containsMatchIn(listing)) return null
            return when {
                KERNEL.containsMatchIn(listing) -> DEBUG
                debuggable -> PROFILE
                else -> RELEASE
            }
        }

        /** `pkgFlags=[ DEBUGGABLE HAS_CODE … ]` in `dumpsys package`; older releases print `flags=[ … ]`. */
        fun isDebuggable(dumpsysPackage: String): Boolean = DEBUGGABLE.containsMatchIn(dumpsysPackage)

        private const val FLUTTER_FILES =
            """lib/[^/]+/libflutter\.so|lib/[^/]+/libapp\.so|flutter_assets/kernel_blob\.bin"""
        private val ENGINE = Regex("""lib/[^/\s]+/libflutter\.so""")
        private val KERNEL = Regex("""flutter_assets/kernel_blob\.bin""")
        private val DEBUGGABLE = Regex("""\b(?:pkgFlags|flags)=\[[^\]]*\bDEBUGGABLE\b""")
    }
}
