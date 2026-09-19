package spock.adb.mcp.tools

import com.android.ddmlib.IDevice
import com.google.gson.JsonObject
import spock.adb.command.AppStorageUnverifiedWriteException
import spock.adb.command.listAppStorage
import spock.adb.command.readAppStorageFile
import spock.adb.command.writeAppStorageFile
import spock.adb.device.ConnectedDevice
import spock.adb.isAppInstall
import spock.adb.mcp.tools.McpShell.truncateForAgent
import spock.adb.storage.AppStoragePaths
import spock.adb.storage.PrefChange
import spock.adb.storage.PrefItem
import spock.adb.storage.PrefType
import spock.adb.storage.PrefValue
import spock.adb.storage.StorageFile
import spock.adb.storage.isEncryptedPreferences

/** `android_list_app_storage` — which preference files an app has. */
class ListAppStorageTool : AdbTool {
    override val name = "android_list_app_storage"
    override val description =
        "List an app's SharedPreferences files (shared_prefs/*.xml) and Preferences DataStore files " +
            "(files/datastore/*.preferences_pb). Goes through run-as, so the app must be a debuggable " +
            "build. Read one with android_read_app_storage."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "Package whose storage to list. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)
        return storageRead(device, packageName) {
            val files = device.listAppStorage(packageName)
            if (files.isEmpty()) {
                "No SharedPreferences or DataStore files found for $packageName."
            } else {
                files.joinToString("\n") { "${it.path}  (${it.kind.label})" }
            }
        }
    }
}

/** `android_read_app_storage` — one file, as typed entries. */
class ReadAppStorageTool : AdbTool {
    override val name = "android_read_app_storage"
    override val description =
        "Read one SharedPreferences or Preferences DataStore file as typed entries: key, type, value. " +
            "Use it to check an app's stored state — onboarding flags, feature toggles, a cached user — " +
            "before or after reproducing a bug. Requires a debuggable build."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        fileArgument()
        string("packageName", "Package that owns the file. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)
        val file = AppStoragePaths.parse(arguments.requiredString("file"))
        return storageRead(device, packageName) {
            val format = file.kind.format
            if (format == null) {
                "${file.path} is a ${file.kind.label} file. It holds the app's own protobuf message, which " +
                    "cannot be decoded without that message's schema."
            } else {
                describeStorage(file, format.read(device.readAppStorageFile(packageName, file)))
                    .truncateForAgent(McpShell.DEFAULT_MAX_CHARS)
            }
        }
    }
}

/**
 * `android_set_app_preference` and `android_delete_app_preference`.
 *
 * Destructive by the letter of the safety model: the change replaces app state that no normal
 * action restores, and the write stops the app. Everything that can be refused is refused before
 * the developer is asked — a key that is not there, an encrypted file, a value that does not fit
 * its type — so a confirmation is only ever shown for a write that will actually be attempted, and
 * it names the value before and after.
 */
abstract class AppPreferenceEditTool : AdbTool {
    final override val safety = ToolSafety.DESTRUCTIVE

    /** @throws IllegalArgumentException when the arguments do not describe a change. */
    protected abstract fun changeFrom(arguments: JsonObject): PrefChange

    /** What the confirmation says will happen, given the entry the file holds now. */
    protected abstract fun summary(change: PrefChange, before: PrefItem?, where: String): String

    protected abstract fun done(change: PrefChange, where: String): String

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val target = context.requireDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)
        val file = AppStoragePaths.parse(arguments.requiredString("file"))
        val edit = Edit(target, packageName, file, changeFrom(arguments), arguments.optionalBoolean("restart", false))

        if (!target.device.isAppInstall(packageName)) return ToolResult.error(notInstalled(packageName))
        return try {
            apply(edit, context)
        } catch (e: AppStorageUnverifiedWriteException) {
            // Not "could not change": the old content is gone, and an agent told otherwise would retry.
            ToolResult.error("The change to ${edit.where} was written but not verified. ${e.message}")
        } catch (e: IllegalArgumentException) {
            ToolResult.error(e.message ?: "Could not change ${file.path} in $packageName.")
        } catch (e: IllegalStateException) {
            ToolResult.error(e.message ?: "Could not change ${file.path} in $packageName.")
        }
    }

    private class Edit(
        val target: ConnectedDevice,
        val packageName: String,
        val file: StorageFile,
        val change: PrefChange,
        val restart: Boolean,
    ) {
        val where: String get() = "${file.path} of $packageName"
    }

    private fun apply(edit: Edit, context: ToolContext): ToolResult {
        val format = requireNotNull(edit.file.kind.format) {
            "${edit.file.path} is a ${edit.file.kind.label} file, which cannot be edited."
        }
        val original = edit.target.device.readAppStorageFile(edit.packageName, edit.file)
        val items = format.read(original)
        require(!items.isEncryptedPreferences()) {
            "${edit.file.path} holds EncryptedSharedPreferences. Its keys and values are ciphertext, so it is " +
                "read-only: an edit would only produce something the app cannot decrypt."
        }
        val content = format.write(original, listOf(edit.change))

        val stopping = if (edit.restart) "stops the app and starts it again" else "stops the app"
        val summary = summary(edit.change, items.lastOrNull { it.key == edit.change.key }, edit.where)
        if (!context.confirmDestructive(name, "$summary This $stopping.", edit.target)) {
            return ToolResult.error("The developer declined to change ${edit.where}.")
        }

        val write = edit.target.device.writeAppStorageFile(
            edit.packageName,
            edit.file,
            content,
            expected = original,
            restart = edit.restart,
        )
        val after = when {
            write.restarted -> "${edit.packageName} was stopped and started again."
            edit.restart -> "${edit.packageName} was stopped; it has no launchable activity to start again."
            else -> "${edit.packageName} was stopped and reads the new state when it next starts."
        }
        // A staged copy the device would not remove is the agent's to report: it holds a copy
        // of the app's data, in a directory every app on the device can see.
        val warning = write.warning?.let { " $it" }.orEmpty()
        return ToolResult.text("${done(edit.change, edit.where)} $after$warning")
    }
}

class SetAppPreferenceTool : AppPreferenceEditTool() {
    override val name = "android_set_app_preference"
    override val description =
        "Set one key in an app's SharedPreferences or Preferences DataStore file, adding it if it is not " +
            "there. Stops the app first so the value is not overwritten from memory. Requires a debuggable " +
            "build and the developer's confirmation. Read the file with android_read_app_storage first."
    override val inputSchema: JsonObject = Schema.obj {
        fileArgument()
        // An empty key is a key both formats can hold, so it is a value here, not an omission.
        string("key", "Preference key. May be empty.", required = true, mayBeEmpty = true)
        enumeration(
            "type",
            "Value type. SharedPreferences cannot hold double or bytes.",
            PrefType.entries.map { it.argument },
            required = true,
        )
        string(
            "value",
            "The value as text: true/false, a number, the string itself, a JSON array of strings for " +
                "string_set, or base64 for bytes.",
            required = true,
        )
        restartArgument()
        string("packageName", "Package that owns the file. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun changeFrom(arguments: JsonObject): PrefChange {
        val typeName = arguments.requiredString("type")
        val type = requireNotNull(PrefType.fromName(typeName)) {
            "'$typeName' is not a preference type. Use one of: ${PrefType.entries.joinToString { it.argument }}."
        }
        return PrefChange.Put(arguments.requiredText("key"), PrefValue.parse(type, valueText(arguments)))
    }

    /** An empty string is a real value, and a string set may arrive as the array itself. */
    private fun valueText(arguments: JsonObject): String {
        val element = arguments.get("value")?.takeIf { !it.isJsonNull }
            ?: throw IllegalArgumentException("Missing required argument 'value'")
        return if (element.isJsonPrimitive) element.asString else element.toString()
    }

    override fun summary(change: PrefChange, before: PrefItem?, where: String): String {
        val put = change as PrefChange.Put
        return when (before) {
            null -> "Add '${plain(put.key)}' = ${shown(put.value)} to $where."
            else -> "Change '${plain(put.key)}' in $where from ${shown(before)} to ${shown(put.value)}."
        }
    }

    override fun done(change: PrefChange, where: String): String =
        "Set '${plain(change.key)}' = ${shown((change as PrefChange.Put).value)} in $where."
}

class DeleteAppPreferenceTool : AppPreferenceEditTool() {
    override val name = "android_delete_app_preference"
    override val description =
        "Remove one key from an app's SharedPreferences or Preferences DataStore file, so the app falls " +
            "back to its default. Stops the app first. Requires a debuggable build and the developer's " +
            "confirmation."
    override val inputSchema: JsonObject = Schema.obj {
        fileArgument()
        string("key", "Preference key to remove. May be empty.", required = true, mayBeEmpty = true)
        restartArgument()
        string("packageName", "Package that owns the file. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun changeFrom(arguments: JsonObject): PrefChange = PrefChange.Remove(arguments.requiredText("key"))

    override fun summary(change: PrefChange, before: PrefItem?, where: String): String =
        "Delete '${plain(change.key)}'${before?.let { " (currently ${shown(it)})" }.orEmpty()} from $where."

    override fun done(change: PrefChange, where: String): String = "Deleted '${plain(change.key)}' from $where."
}

/** Entries as text an agent can read back: `key (type) = value`, one per line. */
internal fun describeStorage(file: StorageFile, items: List<PrefItem>): String = buildString {
    append("${file.path} (${file.kind.label}, ${items.size} ${if (items.size == 1) "entry" else "entries"})")
    if (items.isEncryptedPreferences()) {
        append("\nEncrypted (EncryptedSharedPreferences): keys and values are ciphertext. Read-only.")
    }
    items.forEach { item ->
        append('\n')
        when (item) {
            is PrefItem.Typed -> append("${item.key} (${item.value.type.argument}) = ${item.value.text()}")
            is PrefItem.Opaque -> append("${item.key} (not editable: ${item.description})")
        }
    }
}

private fun storageRead(device: IDevice, packageName: String, read: () -> String): ToolResult {
    if (!device.isAppInstall(packageName)) return ToolResult.error(notInstalled(packageName))
    return runCatching(read).fold(
        onSuccess = { ToolResult.text(it) },
        onFailure = { ToolResult.error(it.message ?: "Could not read the storage of $packageName.") },
    )
}

private fun notInstalled(packageName: String) = "Package '$packageName' is not installed on this device."

private fun shown(item: PrefItem): String = when (item) {
    is PrefItem.Typed -> shown(item.value)
    is PrefItem.Opaque -> plain(item.description)
}

private fun shown(value: PrefValue): String = "${plain(value.text())} (${value.type.argument})"

/**
 * Text from the app's file or from an agent, as one bounded line for a confirmation or a result.
 *
 * Either source is free to put anything in a key or value. Control characters are shown as
 * escapes, so a run of newlines cannot push "This stops the app." out of the dialog, and the
 * length is capped, so the developer can read the whole sentence at a glance.
 */
private fun plain(text: String): String {
    val escaped = buildString {
        text.forEach { char ->
            when {
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char.isISOControl() -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
    }
    return if (escaped.length > MAX_SHOWN_CHARS) escaped.take(MAX_SHOWN_CHARS) + "…" else escaped
}

private const val MAX_SHOWN_CHARS = 120

private fun Schema.ObjectBuilder.fileArgument() = string(
    "file",
    "The file as android_list_app_storage lists it, for example shared_prefs/settings.xml or " +
        "files/datastore/settings.preferences_pb.",
    required = true,
)

private fun Schema.ObjectBuilder.restartArgument() =
    boolean("restart", "Launch the app again after the change. Defaults to false.")
