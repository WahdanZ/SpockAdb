package spock.adb.uitree

import com.android.ddmlib.IDevice
import spock.adb.flutter.FlutterBuildCache

/**
 * Whether a capture shows a Flutter app, asked of the app rather than read off the dump.
 *
 * Flutter's semantics bridge reports `Button`, `EditText` and `View` like any View screen, so a
 * Flutter app was described to agents as "Traditional Android Views" while `flutter_app_status`
 * said it was Flutter. The app that drew the window is the dump's package, and whether that is a
 * Flutter app is [FlutterBuildCache]'s answer: one APK listing per install, remembered.
 */
object FlutterScreen {

    /**
     * [tree], with its framework set by what [isFlutterApp] says of the app that drew it: a Flutter
     * app's View-like screen is [UiFramework.FLUTTER], and anything else's is not, whatever the dump
     * looked like. Null — adb did not answer — leaves the parser's reading. Compose and hybrid
     * screens are left as they are, and so is a screen with the app's own View ids: a native
     * activity inside a Flutter app is still Views.
     */
    fun classify(tree: UiTree, isFlutterApp: (packageName: String) -> Boolean?): UiTree {
        if (tree.framework != UiFramework.VIEWS && tree.framework != UiFramework.FLUTTER) return tree
        val root = tree.root ?: return tree
        val packageName = root.packageName.takeIf { it.isNotBlank() } ?: return tree
        val flutter = isFlutterApp(packageName) ?: return tree
        val framework = if (flutter && !hasNativeViewIds(root)) UiFramework.FLUTTER else UiFramework.VIEWS
        return tree.copy(framework = framework)
    }

    /**
     * The parser's fallback, for a dump with no device to ask: a `Semantics(identifier:)` shows as
     * a bare resource id, where a View's is always `package:id/name`, and Flutter puts a widget's
     * words in the content description, so no node but a text field has text. React Native's
     * `testID` is a bare id too, but its words are text. A Flutter screen with no identifier reads
     * as Views here.
     */
    internal fun looksLikeFlutter(root: UiNode): Boolean {
        val nodes = root.flatten()
        return nodes.any { it.resourceId.isNotBlank() && !it.resourceId.contains(":id/") } &&
            !hasNativeViewIds(root) &&
            nodes.none { it.text.isNotBlank() && !it.className.endsWith("EditText") }
    }

    /**
     * An id of the app's own Views. Flutter's bridge never publishes one, so a screen with one is
     * native even inside a Flutter app: a plugin's activity, say. The platform's own `android:id/…`
     * containers are in every window and do not count.
     */
    private fun hasNativeViewIds(root: UiNode): Boolean =
        root.asSequence().any { it.resourceId.contains(":id/") && !it.resourceId.startsWith("android:id/") }

    /**
     * [classify] on [device], each adb command of the check given [shellSeconds]. Blocking: a
     * `dumpsys package`, at most every ten seconds, and the APK listing once per install.
     */
    fun classifyOn(tree: UiTree, device: IDevice, serial: String, shellSeconds: Long = CHECK_SECONDS): UiTree =
        classify(tree) { packageName ->
            when (val read = FlutterBuildCache.shared.readOn(device, serial, packageName, shellSeconds)) {
                is FlutterBuildCache.Detection.Listed -> read.build != null
                FlutterBuildCache.Detection.AdbFailed -> null
            }
        }

    /** What a capture gives the check: never longer than the capture itself was given. */
    fun checkSeconds(captureSeconds: Long): Long = minOf(captureSeconds, CHECK_SECONDS)

    /** As long as Diagnose gives adb to tell whether an app is Flutter. */
    private const val CHECK_SECONDS = 5L
}
