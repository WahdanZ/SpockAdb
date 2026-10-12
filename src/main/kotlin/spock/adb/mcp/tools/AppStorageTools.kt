package spock.adb.mcp.tools

import com.android.ddmlib.IDevice
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
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
import spock.adb.storage.format
import spock.adb.storage.isEncryptedPreferences

/** `android_list_app_storage` — which preference files an app has. */
class ListAppStorageTool : AdbTool {
    override val name = "android_list_app_storage"
    override val title = "List app storage"
    override val description =
        "List an app's SharedPreferences and Preferences DataStore files. Needs a debuggable build."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        return context.withPackage(arguments) { packageName ->
            storageRead(device, packageName) {
                val files = device.listAppStorage(packageName)
                ToolResult.text(
                    if (files.isEmpty()) {
                        "No SharedPreferences or DataStore files found for $packageName."
                    } else {
                        files.joinToString("\n") { "${it.path}  (${it.kind.label})" }
                    },
                )
            }
        }
    }
}

/**
 * `android_read_app_storage` — one file, as typed entries.
 *
 * Read-only, but not unbounded and not unguarded: one cached JSON value made a single Flutter
 * prefs file 40 000 characters, and the same files hold push and auth tokens. How the entries are
 * cut, filtered and redacted is [StorageView]'s; revealing a redacted value is the developer's call.
 */
class ReadAppStorageTool : AdbTool {
    override val name = "android_read_app_storage"
    override val title = "Read app storage"
    override val description =
        "Read a SharedPreferences or DataStore file as typed entries. Needs a debuggable build. " +
            "Secret-looking values are redacted, best-effort."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        fileArgument()
        string("key", "Only keys containing this, ignoring case.")
        integer(
            "maxValueChars",
            "Cut each value at this length. Default ${StorageView.DEFAULT_MAX_VALUE_CHARS}.",
            StorageView.VALUE_CHARS,
        )
        boolean("showSecrets", "Reveal redacted values if the developer agrees.")
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val target = context.requireDevice(arguments.optionalString("deviceSerial"))
        val device = target.device
        val file = AppStoragePaths.parse(arguments.requiredString("file"))
        val view = StorageView.from(arguments).getOrElse { return ToolResult.error(it.message.orEmpty()) }
        return context.withPackage(arguments) { packageName ->
            storageRead(device, packageName) {
                val format = file.format
                if (format == null) {
                    return@storageRead ToolResult.text(
                        "${file.path} is a ${file.kind.label} file. It holds the app's own protobuf message, " +
                            "which cannot be decoded without that message's schema.",
                    )
                }
                val items = format.read(device.readAppStorageFile(packageName, file))
                val secrets = view.secretKeys(items)
                val where = "${file.path} of $packageName"
                val reveal = when {
                    !view.showSecrets || secrets.isEmpty() -> StorageView.Reveal.NOT_ASKED
                    context.confirmDestructive(
                        name,
                        revealSummary(secrets, where),
                        target,
                        ConfirmationWording.REVEAL_SECRETS,
                    ) -> StorageView.Reveal.ALLOWED
                    else -> StorageView.Reveal.DECLINED
                }
                val text = view.describe(file, items, reveal).truncateForAgent(McpShell.DEFAULT_MAX_CHARS)
                if (reveal != StorageView.Reveal.ALLOWED) return@storageRead ToolResult.text(text)

                // The agent sees the values; the activity history, which is written to disk and shown in
                // the panel, keeps the redacted rendering. idea.log records that it happened, by key.
                val keys = secrets.joinToString { "'${plain(it)}'" }
                val serial = target.serialNumber
                LOG.info("MCP $name: secret values revealed with the developer's approval in $where on $serial: $keys")
                ToolResult.text(text).copy(
                    historyText = view.describe(file, items, StorageView.Reveal.RECORDED)
                        .truncateForAgent(McpShell.DEFAULT_MAX_CHARS),
                )
            }
        }
    }

    private fun revealSummary(keys: List<String>, where: String): String {
        val named = keys.take(MAX_NAMED_KEYS).joinToString { "'${plain(it)}'" } +
            if (keys.size > MAX_NAMED_KEYS) " and ${keys.size - MAX_NAMED_KEYS} more" else ""
        return "Show the agent ${keys.size} value(s) from $where that are redacted because they look " +
            "secret: $named."
    }

    private companion object {
        const val MAX_NAMED_KEYS = 8
        val LOG = Logger.getInstance(ReadAppStorageTool::class.java)
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
        return context.withResolvedPackage(arguments) { resolved ->
            val packageName = resolved.name
            val file = AppStoragePaths.parse(arguments.requiredString("file"))
            val restart = arguments.optionalBoolean("restart", false)
            val edit = Edit(target, resolved, file, changeFrom(arguments), restart)

            if (!target.device.isAppInstall(packageName)) {
                return@withResolvedPackage ToolResult.error(notInstalled(packageName))
            }
            try {
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
    }

    private class Edit(
        val target: ConnectedDevice,
        val app: ResolvedPackage,
        val file: StorageFile,
        val change: PrefChange,
        val restart: Boolean,
    ) {
        val packageName: String get() = app.name
        val where: String get() = "${file.path} of $packageName"
    }

    private fun apply(edit: Edit, context: ToolContext): ToolResult {
        val format = requireNotNull(edit.file.format) {
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
        if (!context.confirmDestructive(name, edit.app.confirming("$summary This $stopping."), edit.target)) {
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
    override val title = "Set app preference"
    override val description =
        "Set or add one key in a SharedPreferences or DataStore file, stopping the app first. Needs a " +
            "debuggable build; the developer confirms."
    override val inputSchema: JsonObject = Schema.obj {
        fileArgument()
        // An empty key is a key both formats can hold, so it is a value here, not an omission.
        string("key", "Preference key. May be empty.", required = true, mayBeEmpty = true)
        enumeration(
            "type",
            "Value type. Not double or bytes in SharedPreferences; string_list only in FlutterSharedPreferences.xml.",
            PrefType.entries.map { it.argument },
            required = true,
        )
        string(
            "value",
            "As text: true/false, a number, the string, a JSON string array for string_set/string_list, " +
                "base64 for bytes.",
            required = true,
        )
        restartArgument()
        packageName()
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
    override val title = "Delete app preference"
    override val description =
        "Remove one key from a SharedPreferences or DataStore file, stopping the app first. Needs a " +
            "debuggable build; the developer confirms."
    override val inputSchema: JsonObject = Schema.obj {
        fileArgument()
        string("key", "Preference key to remove. May be empty.", required = true, mayBeEmpty = true)
        restartArgument()
        packageName()
        deviceSerial()
    }

    override fun changeFrom(arguments: JsonObject): PrefChange = PrefChange.Remove(arguments.requiredText("key"))

    override fun summary(change: PrefChange, before: PrefItem?, where: String): String =
        "Delete '${plain(change.key)}'${before?.let { " (currently ${shown(it)})" }.orEmpty()} from $where."

    override fun done(change: PrefChange, where: String): String = "Deleted '${plain(change.key)}' from $where."
}

private fun storageRead(device: IDevice, packageName: String, read: () -> ToolResult): ToolResult {
    if (!device.isAppInstall(packageName)) return ToolResult.error(notInstalled(packageName))
    return runCatching(read).fold(
        onSuccess = { it },
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
    val escaped = escapeControlChars(text)
    return if (escaped.length > MAX_SHOWN_CHARS) escaped.take(MAX_SHOWN_CHARS) + "…" else escaped
}

/** Control characters as escapes, so text from the app's file stays on the one line it is given. */
internal fun escapeControlChars(text: String): String = buildString {
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

private const val MAX_SHOWN_CHARS = 120

private fun Schema.ObjectBuilder.fileArgument() = string(
    "file",
    "As android_list_app_storage lists it, e.g. shared_prefs/settings.xml.",
    required = true,
)

private fun Schema.ObjectBuilder.restartArgument() =
    boolean("restart", "Launch the app again after the change.")
