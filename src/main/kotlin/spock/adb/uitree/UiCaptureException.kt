package spock.adb.uitree

/**
 * A screen capture that did not produce a tree, and why.
 *
 * Before this a lost device or a timeout reached an agent as whatever ddmlib's exception
 * happened to say, often a bare class name, and a caller had no way to tell "try again" from
 * "stop". [kind] is that distinction.
 *
 * An [IllegalStateException] so that callers catching what a capture has always thrown keep
 * catching it.
 */
class UiCaptureException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {

    enum class Kind(
        /** Whether the same capture, repeated on the same device, has a fair chance of working. */
        val retryable: Boolean,
    ) {
        /** The caller asked to stop. Nothing is wrong with the device. */
        CANCELLED(retryable = false),

        /** The device is gone, offline, or adb refused to talk to it. */
        DEVICE_UNAVAILABLE(retryable = false),

        /** A command did not finish in time. The device may be alive but badly loaded. */
        TIMED_OUT(retryable = false),

        /** `uiautomator` refused: screen off, a secure window, or a UI still animating. */
        DUMP_REFUSED(retryable = true),

        /** `uiautomator` reported success and wrote nothing. */
        EMPTY_DUMP(retryable = true),
    }
}
