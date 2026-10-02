package spock.adb.flutter.dtd

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * One running Dart Tooling Daemon, as its registry file describes it.
 *
 * [workspaceRoots] is where it was started for — the Flutter project `flutter run` ran in. Empty
 * when the file names none. [toString] never carries the daemon's secret.
 */
class DtdRegistryEntry(
    val uri: DtdUri,
    val pid: Long,
    /** When the daemon started, in ms since the epoch; null when the file does not say. */
    val epoch: Long?,
    val dartVersion: String?,
    val workspaceRoots: List<String>,
    val file: Path,
) {
    override fun toString(): String =
        "DTD pid $pid at ${uri.redacted()} for ${workspaceRoots.ifEmpty { listOf("no workspace") }.joinToString()}"
}

/**
 * Lists the Dart Tooling Daemons running on this machine, from the files they register in.
 *
 * Every DTD writes `<dir>/<pid>` holding `{"wsUri", "epoch", "pid", "dartVersion",
 * "workspaceRoot"}` — what `dart tooling-daemon --list` reads (spike S12). When the daemon dies
 * its file stays, so an entry counts only while its pid is alive and that process is not younger
 * than the entry ([isRunning]). The files are any local process's to write: each is read
 * leniently, a bad or oversized one is skipped, and an address that is not on loopback is
 * refused ([DtdUri.parse]).
 *
 * Blocking (file reads); never throws. [dirs] and [running] are for tests.
 */
class DtdRegistry(
    private val dirs: List<Path> = defaultDirs(),
    private val running: (DtdRegistryEntry) -> Boolean = { isRunning(it) },
) {

    /** The live daemons, newest first. */
    fun entries(): List<DtdRegistryEntry> =
        dirs.distinct()
            .flatMap(::filesIn)
            .mapNotNull(::read)
            .filter(running)
            .distinctBy { it.uri }
            .sortedByDescending { it.epoch ?: 0L }

    private fun filesIn(dir: Path): List<Path> = try {
        if (!Files.isDirectory(dir)) {
            emptyList()
        } else {
            Files.list(dir).use { files -> files.filter { Files.isRegularFile(it) }.toList() }
        }
    } catch (e: IOException) {
        log.info("Could not list the DTD registry $dir: ${e.message}")
        emptyList()
    } catch (e: UncheckedIOException) {
        log.info("Could not list the DTD registry $dir: ${e.message}")
        emptyList()
    } catch (e: SecurityException) {
        log.info("Could not list the DTD registry $dir: ${e.message}")
        emptyList()
    }

    private fun read(file: Path): DtdRegistryEntry? {
        val bytes = try {
            // Read up to the cap, not by a size asked first: the file can grow in between.
            Files.newInputStream(file).use { it.readNBytes(MAX_FILE_BYTES + 1) }
        } catch (e: IOException) {
            log.info("Could not read the DTD registry file ${file.fileName}: ${e.message}")
            return null
        } catch (e: SecurityException) {
            log.info("Could not read the DTD registry file ${file.fileName}: ${e.message}")
            return null
        }
        if (bytes.size > MAX_FILE_BYTES) {
            log.info("Skipping the DTD registry file ${file.fileName}: larger than $MAX_FILE_BYTES bytes")
            return null
        }
        return parse(String(bytes, Charsets.UTF_8), file)
    }

    companion object {
        /** A registry file is a few hundred bytes; anything far bigger is not one. */
        const val MAX_FILE_BYTES = 64 * 1024

        /**
         * How much later than its entry's `epoch` the process may seem to have started: the two
         * are read from different clocks at different precisions. Beyond it, the pid is reused.
         */
        private const val START_ALLOWANCE_MS = 2_000L

        private val log = Logger.getInstance(DtdRegistry::class.java)

        /**
         * One registry file's entry, or null when it is not one. The pid comes from the JSON,
         * else from the file name, which is the pid.
         */
        fun parse(text: String, file: Path): DtdRegistryEntry? {
            val json = try {
                JsonParser.parseString(text) as? JsonObject
            } catch (_: JsonParseException) {
                null
            } ?: return null
            val uri = json.text("wsUri")?.let(DtdUri::parseOrNull) ?: return null
            val pid = json.number("pid") ?: file.fileName?.toString()?.toLongOrNull() ?: return null
            return DtdRegistryEntry(
                uri = uri,
                pid = pid,
                epoch = json.number("epoch"),
                dartVersion = json.text("dartVersion"),
                workspaceRoots = roots(json.get("workspaceRoot")),
                file = file,
            )
        }

        /**
         * Where the registry lives, for this OS. Only macOS is seen (S12):
         * `~/Library/Application Support/Dart/dtd`. That is `applicationConfigHome('Dart')` in
         * the Dart SDK's cli_util, so Linux and Windows are guessed from the same function and
         * are **unverified (spike S23)**: Linux `$XDG_CONFIG_HOME/{Dart,dart}/dtd`, then
         * `~/.config/{Dart,dart}/dtd` and `~/.dart-tool/dtd`; Windows `%APPDATA%\Dart\dtd`. A
         * directory that does not exist is skipped, so a wrong guess finds nothing rather than
         * failing.
         */
        fun defaultDirs(
            osName: String = System.getProperty("os.name").orEmpty(),
            home: String = System.getProperty("user.home").orEmpty(),
            env: (String) -> String? = System::getenv,
        ): List<Path> {
            val os = osName.lowercase()
            val candidates = when {
                os.startsWith("mac") -> listOf("$home/Library/Application Support/Dart/dtd")
                os.startsWith("windows") -> listOfNotNull(env("APPDATA")?.let { "$it\\Dart\\dtd" })
                else -> listOfNotNull(
                    env("XDG_CONFIG_HOME")?.let { "$it/Dart/dtd" },
                    env("XDG_CONFIG_HOME")?.let { "$it/dart/dtd" },
                    "$home/.config/Dart/dtd",
                    "$home/.config/dart/dtd",
                    "$home/.dart-tool/dtd",
                )
            }
            return candidates.mapNotNull(::pathOrNull).distinct()
        }

        /**
         * Whether the daemon [entry] describes still runs: its pid is alive, and the process
         * behind it did not start after the daemon wrote its `epoch` — then the pid was reused by
         * something else. When either start time is unknown the pid alone decides; connecting to
         * a reused pid's address fails, which is handled.
         */
        fun isRunning(
            entry: DtdRegistryEntry,
            alive: (Long) -> Boolean = ::isAlive,
            startedAt: (Long) -> Long? = ::startMillis,
        ): Boolean {
            if (!alive(entry.pid)) return false
            val epoch = entry.epoch ?: return true
            val started = startedAt(entry.pid) ?: return true
            return started <= epoch + START_ALLOWANCE_MS
        }

        private fun isAlive(pid: Long): Boolean = try {
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        } catch (_: SecurityException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }

        private fun startMillis(pid: Long): Long? = try {
            ProcessHandle.of(pid).flatMap { it.info().startInstant() }.map { it.toEpochMilli() }.orElse(null)
        } catch (_: SecurityException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }

        private fun pathOrNull(path: String): Path? = try {
            Path.of(path)
        } catch (_: InvalidPathException) {
            null
        }

        /** S12 saw one path; a list is read too, in case a daemon serves several roots. */
        private fun roots(element: JsonElement?): List<String> = when {
            element is JsonPrimitive && element.isString -> listOf(element.asString)
            element is JsonArray -> element.filterIsInstance<JsonPrimitive>().filter { it.isString }.map { it.asString }
            else -> emptyList()
        }.filter { it.isNotBlank() }

        private fun JsonObject.text(key: String): String? =
            (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString

        /** A number, or a string holding one: lenient, the file is not ours. */
        private fun JsonObject.number(key: String): Long? {
            val value = get(key) as? JsonPrimitive ?: return null
            return when {
                value.isNumber -> value.asNumber.toLong()
                value.isString -> value.asString.trim().toLongOrNull()
                else -> null
            }
        }
    }
}
