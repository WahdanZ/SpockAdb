package spock.adb.device

import com.android.ddmlib.IDevice
import com.intellij.openapi.progress.ProcessCanceledException

/**
 * Turns the raw ddmlib device list into [ConnectedDevice]s without ever throwing.
 *
 * The device list is read on a pooled thread and delivered to a UI callback. When that read
 * threw, the callback simply never ran and the dropdown stayed empty with no message and no
 * log entry — the failure mode that hid a broken ADB lookup entirely.
 *
 * Every failure is now reported through [onError] and degraded: a device whose properties
 * cannot be read is dropped rather than taking the whole list with it.
 */
class DeviceLister(
    private val devicesSupplier: () -> List<IDevice>?,
    private val readInfo: (IDevice) -> DeviceInfo = DeviceInfoReader::read,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) {

    // Catching Throwable is the point. Anything escaping this method kills the background
    // task that feeds the device dropdown, leaving it empty with no message and nothing in
    // the log — which is exactly how a broken ADB lookup went unnoticed. ProcessCanceledException
    // is rethrown so IDE cancellation still works.
    @Suppress("TooGenericExceptionCaught")
    fun list(): List<ConnectedDevice> {
        val devices = try {
            devicesSupplier()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Throwable) {
            onError("Could not read the connected device list from ADB", e)
            null
        } ?: return emptyList()

        return devices.mapNotNull { device ->
            try {
                ConnectedDevice(device, readInfo(device))
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Throwable) {
                onError("Could not read properties of a connected device", e)
                null
            }
        }
    }
}
