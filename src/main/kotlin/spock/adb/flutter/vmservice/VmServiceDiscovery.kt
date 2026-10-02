package spock.adb.flutter.vmservice

/** Where a VM Service address came from. */
enum class VmServiceSource(val label: String) {
    /** Pasted by the developer: `flutter run`'s address or a DevTools link. */
    PASTED("pasted"),

    /** The engine's own announcement in logcat, reached through an `adb forward`. */
    LOGCAT("logcat"),
}

/**
 * What answers on an open connection, asked rather than assumed from where the address came from:
 * DDS serves `getDartDevelopmentServiceVersion`, the VM itself answers it with "method not found".
 */
enum class ConnectionKind {
    /** The Dart Development Service: shared with `flutter run` and DevTools, so Spock may write. */
    DDS,

    /**
     * The VM itself, with no DDS in front of it: the app was started without `flutter run` or
     * `flutter attach`. A client that stays here keeps them from starting DDS (spike S10), so a
     * session does not stay unless asked to, and then writes nothing.
     */
    DIRECT_NO_DDS,
}

/**
 * A VM Service Spock could connect to.
 *
 * [ddsLikely] says whether the address is probably the Dart Development Service's — the one
 * `flutter run` hands out, which any number of clients can share — rather than the VM's own. It
 * is a hint for trying candidates in order, nothing more: a device address forwarded by hand
 * looks like a DDS one, and a VM address may hand its clients to DDS. What a connection really
 * is, and so whether it may write, is settled by asking once it is open: [ConnectionKind] [FR1].
 */
abstract class VmServiceCandidate(
    val source: VmServiceSource,
    val ddsLikely: Boolean,
) {
    /** What to show the developer. Never carries the auth code. */
    abstract val description: String

    /** The address to connect to, preparing the way first if needed (an `adb forward`). Blocking. */
    abstract fun open(): VmServiceUri

    /** Undoes what [open] set up. Blocking; safe to call when [open] never ran. */
    open fun release() = Unit

    override fun toString(): String = description
}

/**
 * [original]'s VM handed Spock to DDS at [target] (a [VmServiceRedirectException]). Releasing it
 * releases [original] — its `adb forward`.
 */
internal class HandedToDdsCandidate(val original: VmServiceCandidate, private val target: VmServiceUri) :
    VmServiceCandidate(original.source, ddsLikely = true) {
    override val description: String get() = "${original.description}, handed to DDS at ${target.redacted()}"
    override fun open(): VmServiceUri = target
    override fun release() = original.release()
}

/** Finds VM Services. Blocking: call it from a pooled thread. */
fun interface VmServiceDiscovery {
    fun discover(): List<VmServiceCandidate>
}

/**
 * The address the developer pasted.
 *
 * Likely DDS: the forms `flutter run` and DevTools print are both the DDS address on the host.
 * But a device address forwarded by hand cannot be told apart from it, so the session asks once
 * connected, and leaves a VM with no DDS behind it alone.
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
