package spock.adb.flutter

/**
 * What a session sends through [FlutterSession.callUiExtension] and [FlutterSession.callVm] on a
 * connection that only watches (not DDS) — fail closed: an extension must be a known read, and a
 * VM-level RPC must not be a known mutator.
 */
internal object ReadOnlyCalls {

    /** The argument that makes a bool extension set its value rather than report it. */
    const val SET_PARAM = "enabled"

    /** Reads that take arguments (`updatedSince`, `id`), none of which change the app. */
    private val READ_EXTENSIONS = setOf(
        "ext.dart.io.getHttpProfile",
        "ext.dart.io.getHttpProfileRequest",
    )

    /** Bool extensions: a read only when called with no arguments. */
    private val BOOL_READS = setOf(HttpLogging.HTTP_LOGGING, FlutterIsolate.STRUCTURED_ERRORS)

    /** VM Service RPCs that change the app or the VM: run code, resume, kill, set, clear. */
    private val VM_MUTATORS = setOf(
        "resume",
        "kill",
        "pause",
        "reloadSources",
        "invoke",
        "requestHeapSnapshot",
        "_flutter.runInView",
        "_flutter.setAssetBundlePath",
    )

    /** `evaluate`, `evaluateInFrame`, `setFlag`, `setIsolatePauseMode`, `addBreakpoint…`, `clearCpuSamples`… */
    private val VM_MUTATOR_PREFIXES = listOf("evaluate", "set", "add", "remove", "clear")

    /** Whether [method] with [params] only reads, so a read-only connection may send it. */
    fun isReadExtension(method: String, params: Map<String, String>): Boolean = when {
        SET_PARAM in params -> false
        method in BOOL_READS -> params.isEmpty()
        else -> method in READ_EXTENSIONS
    }

    /** Whether [method] is a VM-level RPC known to change the app or the VM. */
    fun isVmMutator(method: String): Boolean =
        method in VM_MUTATORS || VM_MUTATOR_PREFIXES.any { method.startsWith(it) }
}
