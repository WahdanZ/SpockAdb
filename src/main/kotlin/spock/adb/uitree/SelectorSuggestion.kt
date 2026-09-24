package spock.adb.uitree

/**
 * A selector for one captured node, written out in each form a developer pastes it into.
 *
 * Built from the most stable thing the node offers — its test tag, else its whole text, else its
 * whole content description — and always as a whole-value match: a substring selector that
 * happens to find one node today finds two after the next copy change. [check] says whether it
 * finds only this node on the screen it was captured from.
 */
internal data class SelectorSuggestion(
    val basis: Basis,
    val selector: UiSelector,
    /** Arguments for the `android_*_ui_element` MCP tools. */
    val mcpJson: String,
    /** A `composeTestRule` finder; null on a Views-only screen, where Compose tests find nothing. */
    val composeTest: String?,
    /** A UI Automator `BySelector`. */
    val uiAutomator: String,
) {

    enum class Basis(val label: String) {
        TEST_TAG("test tag"),
        TEXT("text"),
        CONTENT_DESCRIPTION("content description"),
    }

    /** Checks this selector against the tree it was built from. In memory, so cheap enough for the EDT. */
    fun check(tree: UiTree, node: UiNode): SelectorCheck = SelectorCheck.of(tree, node, selector)

    companion object {

        /** Null when the node has no test tag, text or content description to select it by. */
        fun forNode(node: UiNode, framework: UiFramework): SelectorSuggestion? {
            val tag = node.testTag
            val compose = framework != UiFramework.VIEWS
            return when {
                tag != null -> SelectorSuggestion(
                    basis = Basis.TEST_TAG,
                    // exactTag: the MCP default matches a tag as a substring, so "row" would also find "row_title".
                    selector = UiSelector(testTag = tag, exactTag = true),
                    mcpJson = "{\"testTag\":${json(tag)},\"exactTag\":true}",
                    composeTest = "composeTestRule.onNodeWithTag(${kotlin(tag)})".takeIf { compose },
                    // UI Automator matches the resource id as the device reports it, package prefix and all.
                    uiAutomator = "By.res(${java(node.resourceId)})",
                )
                node.text.isNotBlank() -> SelectorSuggestion(
                    basis = Basis.TEXT,
                    selector = UiSelector(text = node.text, exact = true),
                    mcpJson = "{\"text\":${json(node.text)},\"exact\":true}",
                    composeTest = "composeTestRule.onNodeWithText(${kotlin(node.text)})".takeIf { compose },
                    uiAutomator = "By.text(${java(node.text)})",
                )
                node.contentDescription.isNotBlank() -> SelectorSuggestion(
                    basis = Basis.CONTENT_DESCRIPTION,
                    selector = UiSelector(contentDescription = node.contentDescription, exact = true),
                    mcpJson = "{\"contentDescription\":${json(node.contentDescription)},\"exact\":true}",
                    composeTest = "composeTestRule.onNodeWithContentDescription(${kotlin(node.contentDescription)})"
                        .takeIf { compose },
                    uiAutomator = "By.desc(${java(node.contentDescription)})",
                )
                else -> null
            }
        }

        /** A JSON string literal. JSON and Java escape the same characters, the same way. */
        fun json(value: String): String = quote(value, ::escape)

        /** A Java string literal. */
        fun java(value: String): String = quote(value, ::escape)

        /** A Kotlin string literal: as Java, plus `$`, which would otherwise start a template. */
        fun kotlin(value: String): String = quote(value) { char -> if (char == '$') "\\$" else escape(char) }

        private fun escape(char: Char): String? = when (char) {
            '"' -> "\\\""
            '\\' -> "\\\\"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> if (char < ' ') "\\u" + char.code.toString(HEX).padStart(UNICODE_ESCAPE_DIGITS, '0') else null
        }

        private const val HEX = 16
        private const val UNICODE_ESCAPE_DIGITS = 4

        private inline fun quote(value: String, escape: (Char) -> String?): String = buildString {
            append('"')
            value.forEach { char -> append(escape(char) ?: char) }
            append('"')
        }
    }
}

/**
 * How many nodes a selector finds on the captured screen, and whether this node is one of them.
 *
 * Counted with [UiTreeSearch], the same search the element actions run, and an ambiguous
 * selector carries the refusal those actions would give — the candidates, and which field can
 * tell them apart.
 */
internal data class SelectorCheck(
    val matches: Int,
    val matchesNode: Boolean,
    /** Why an element action would refuse this selector, when it would. */
    val ambiguity: String?,
) {
    val isUnique: Boolean get() = matches == 1 && matchesNode

    fun describe(): String = when {
        // Selectors only match nodes with an area, so a zero-size node is never found by its own.
        !matchesNode -> "Does not find this node: it has no visible area, and selectors skip those"
        matches == 1 -> "Unique on this screen"
        else -> "Matches $matches nodes on this screen — an element action would refuse it"
    }

    companion object {
        fun of(tree: UiTree, node: UiNode, selector: UiSelector): SelectorCheck {
            val found = UiTreeSearch.findAll(tree, selector)
            val ambiguity = runCatching { UiTreeSearch.findUnique(tree, selector) }
                .exceptionOrNull()
                ?.message
            return SelectorCheck(found.size, found.any { it === node }, ambiguity)
        }
    }
}
