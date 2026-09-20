package spock.adb.assistant

/**
 * Whether the in-IDE AI features are offered at all.
 *
 * Both are **off**, at the maintainer's request. Nothing behind them is deleted: the agent loop,
 * the tool gate and its confirmations, the audit trail, the Logcat context builder, the
 * redaction step and all of their tests are untouched and still run. These decide only whether
 * there is a way in.
 *
 * One object rather than a constant in each panel, because they have to agree — a Logcat handoff
 * with no Assistant tab to hand anything to would be a dead button, and a reader looking for
 * "why is there no Assistant" should find one answer, in one place.
 */
object AssistantFeature {

    /** The Assistant tab in the tool window, its IDE action, and its settings section. */
    const val TAB_VISIBLE = false

    /** `Ask AI` in the Logcat tab. Requires [TAB_VISIBLE] to be of any use. */
    const val LOGCAT_HANDOFF_VISIBLE = false
}
