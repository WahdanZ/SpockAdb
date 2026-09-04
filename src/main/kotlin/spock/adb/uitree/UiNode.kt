package spock.adb.uitree

/**
 * One node of the on-screen UI, as published to the accessibility tree.
 *
 * This is deliberately a *semantics* model rather than a View model. Jetpack Compose has no
 * View hierarchy to inspect — it publishes semantics into the accessibility tree, which is
 * the same place `uiautomator` reads from. Modelling semantics therefore covers Views,
 * Compose and hybrid screens with one representation, without binding the plugin to any
 * Compose runtime version.
 */
data class UiNode(
    val className: String,
    val packageName: String,
    /** Visible text. For Compose this is the `text` semantics property. */
    val text: String,
    val contentDescription: String,
    /**
     * Accessibility resource id.
     *
     * For Views this is the `@id/...` name. For Compose it carries `Modifier.testTag`, but
     * **only** when the app opts in with `testTagsAsResourceId = true`; otherwise Compose
     * test tags are not visible over ADB at all. See [UiTree.testTagSupport].
     */
    val resourceId: String,
    val bounds: Bounds,
    val clickable: Boolean,
    val longClickable: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val focusable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val password: Boolean,
    val children: List<UiNode>,
) {

    /** Compose test tag, when the app exposed it as a resource id. */
    val testTag: String?
        get() = resourceId.substringAfterLast('/', resourceId).takeIf {
            it.isNotBlank() && !resourceId.startsWith("android:id/")
        }

    /** A short human label: the most identifying thing this node offers. */
    val label: String
        get() = sequenceOf(text, contentDescription, testTag.orEmpty())
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

    /** Interactive in the sense an agent cares about: something it can act on. */
    val isInteractive: Boolean get() = clickable || longClickable || scrollable || checkable

    /**
     * This node and every descendant, depth first.
     *
     * Written as a plain recursive walk rather than a `sequence { }` builder on purpose.
     * The builder compiles to a coroutine state machine that references
     * `kotlin.coroutines.jvm.internal.SpillingKt`, which is absent from the Kotlin stdlib
     * bundled with 2023.1 IDEs — and since the plugin uses the IDE's bundled stdlib, that is
     * a NoSuchClassError at runtime. Caught by Plugin Verifier; see docs/COMPATIBILITY.md.
     */
    fun flatten(): List<UiNode> = ArrayList<UiNode>().also { collectInto(it) }

    fun asSequence(): Sequence<UiNode> = flatten().asSequence()

    private fun collectInto(target: MutableList<UiNode>) {
        target.add(this)
        children.forEach { it.collectInto(target) }
    }

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val width: Int get() = right - left
        val height: Int get() = bottom - top

        /** A zero-area node cannot be tapped and is almost always a layout wrapper. */
        val isVisible: Boolean get() = width > 0 && height > 0

        override fun toString() = "[$left,$top][$right,$bottom]"
    }
}

/**
 * The whole visible UI, plus what could be determined about how it was built.
 */
data class UiTree(
    val root: UiNode?,
    val framework: UiFramework,
    val testTagSupport: TestTagSupport,
) {
    fun nodes(): Sequence<UiNode> = root?.asSequence() ?: emptySequence()

    /** Whether Compose test tags can be seen at all on this screen. */
    enum class TestTagSupport {
        /** Not a Compose screen; resource ids are View ids. */
        NOT_APPLICABLE,

        /** Compose is present and at least one node exposes a test tag as a resource id. */
        AVAILABLE,

        /**
         * Compose is present but no node exposes a resource id, which almost always means
         * the app has not set `testTagsAsResourceId = true`. Test tags cannot be read over
         * ADB in that case, and matching falls back to text and content description.
         */
        UNAVAILABLE,
    }
}

/**
 * How the visible screen is built.
 *
 * An agent must not assume `Activity → View hierarchy`: on a Compose screen that model is
 * simply wrong, and coordinates guessed from it will miss.
 */
enum class UiFramework(val description: String) {
    VIEWS("Traditional Android Views"),
    COMPOSE("Jetpack Compose"),
    HYBRID("Mixed Views and Jetpack Compose"),
    UNKNOWN("Could not be determined"),
}
