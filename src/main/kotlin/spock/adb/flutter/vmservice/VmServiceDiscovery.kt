package spock.adb.flutter.vmservice

/** Where a VM Service address came from. */
enum class VmServiceSource(val label: String) {
    /** Pasted by the developer: `flutter run`'s address or a DevTools link. */
    PASTED("pasted"),

    /** The engine's own announcement in logcat, reached through an `adb forward`. */
    LOGCAT("logcat"),
}

/**
 * A VM Service Spock could connect to.
 *
 * [ddsLikely] says whether the address is probably the Dart Development Service's — the one
 * `flutter run` hands out, which any number of clients can share. When false the address is
 * the VM's own ([direct]): connecting there before DDS has started makes `flutter attach` fail
 * to start it [FR1], so a direct candidate is a read-only last resort.
 */
abstract class VmServiceCandidate(
    val source: VmServiceSource,
    val ddsLikely: Boolean,
) {
    val direct: Boolean get() = !ddsLikely

    /** What to show the developer. Never carries the auth code. */
    abstract val description: String

    /** The address to connect to, preparing the way first if needed (an `adb forward`). Blocking. */
    abstract fun open(): VmServiceUri

    /** Undoes what [open] set up. Blocking; safe to call when [open] never ran. */
    open fun release() = Unit

    override fun toString(): String = description
}

/** Finds VM Services. Blocking: call it from a pooled thread. */
fun interface VmServiceDiscovery {
    fun discover(): List<VmServiceCandidate>
}

/**
 * The address the developer pasted.
 *
 * Taken as DDS: the forms `flutter run` and DevTools print are both the DDS address on the host.
 * A device address forwarded by hand cannot be told apart from it.
 */
class PastedUriDiscovery(private val text: String) : VmServiceDiscovery {

    /** @throws IllegalArgumentException with a message fit to show, as [VmServiceUri.parse]. */
    override fun discover(): List<VmServiceCandidate> = listOf(PastedCandidate(VmServiceUri.parse(text)))

    private class PastedCandidate(private val uri: VmServiceUri) :
        VmServiceCandidate(VmServiceSource.PASTED, ddsLikely = true) {
        override val description: String get() = "${uri.redacted()} (pasted)"
        override fun open(): VmServiceUri = uri
    }
}
