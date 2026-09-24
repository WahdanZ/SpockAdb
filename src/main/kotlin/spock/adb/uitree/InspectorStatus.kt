package spock.adb.uitree

/** A device as the Inspector names it: its serial to compare, its display name to show. */
internal data class DeviceLabel(val serial: String, val name: String)

/**
 * What the Inspector's status line says.
 *
 * Derived from the whole state rather than set by whoever spoke last. It used to be set by
 * whoever spoke last, and the device list refreshing — which it does on its own — replaced a
 * capture's node count with "press Capture UI" while the captured tree was still on screen.
 */
internal object InspectorStatus {

    fun text(
        selected: DeviceLabel?,
        capturedFrom: DeviceLabel?,
        tree: UiTree?,
        capturing: Boolean,
        failure: Throwable?,
    ): String = when {
        capturing -> "Capturing ${selected?.name ?: "the device"}…"
        failure != null -> captureFailureText(failure)
        tree != null && capturedFrom != null && capturedFrom.serial != selected?.serial ->
            stale(capturedFrom, selected)
        tree != null -> captured(tree)
        selected != null -> "Ready to capture ${selected.name}."
        else -> NO_DEVICE
    }

    const val NO_DEVICE = "No device selected. Choose one in the Devices tab."

    /** The tree stays: it is still what that device showed, and still worth reading. */
    private fun stale(capturedFrom: DeviceLabel, selected: DeviceLabel?): String =
        if (selected == null) {
            "Captured from ${capturedFrom.name} — no device selected now; choose one to capture again."
        } else {
            "Captured from ${capturedFrom.name} — device changed; capture again."
        }

    private fun captured(tree: UiTree): String {
        val total = tree.nodes().count()
        val interactive = tree.nodes().count { it.isInteractive && it.bounds.isVisible }
        return "$total nodes · $interactive interactive"
    }
}

/**
 * What the status line says when a capture fails, worded for the person at the IDE. The shared
 * message only names the device and says it is gone; an agent is pointed at
 * `android_list_devices`, which is no use here, so this points at what a person can do.
 */
internal fun captureFailureText(failure: Throwable): String {
    val message = failure.message ?: failure.javaClass.simpleName
    val advice = when ((failure as? UiCaptureException)?.kind) {
        UiCaptureException.Kind.DEVICE_UNAVAILABLE ->
            " Reconnect it, or choose another device in the Devices tab, then capture again."
        else -> ""
    }
    return "Capture failed: $message$advice"
}
