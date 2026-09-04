package spock.adb.uitree

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses `uiautomator dump` XML into a [UiTree].
 *
 * `uiautomator` reads the **accessibility tree**, not the View hierarchy — which is exactly
 * why it works for Compose. Compose publishes its semantics there, so one parser covers
 * Views, Compose and hybrid screens without the plugin depending on any Compose artifact or
 * pinning a Compose version.
 *
 * Kept pure so it can be tested against dumps captured from real devices.
 */
object UiTreeParser {

    private const val COMPOSE_VIEW_MARKER = "androidx.compose.ui.platform.AndroidComposeView"
    private const val COMPOSE_LEGACY_MARKER = "androidx.compose.ui.platform.ComposeView"

    /**
     * Widgets that actually render content, as opposed to layout containers.
     *
     * Every Compose app still has `android.widget.FrameLayout` for the decor view and
     * `android:id/content`, so treating any `android.widget.*` class as evidence of Views
     * would classify every pure-Compose screen as hybrid. Only content widgets count.
     */
    private val VIEW_CONTENT_WIDGETS = listOf(
        "android.widget.TextView",
        "android.widget.Button",
        "android.widget.EditText",
        "android.widget.ImageView",
        "android.widget.ImageButton",
        "android.widget.CheckBox",
        "android.widget.RadioButton",
        "android.widget.Switch",
        "android.widget.SeekBar",
        "android.widget.ProgressBar",
        "android.widget.Spinner",
        "android.widget.ListView",
        "androidx.appcompat.widget.",
        "androidx.recyclerview.",
        "androidx.viewpager",
        "com.google.android.material.",
    )

    fun parse(xml: String): UiTree {
        val document = runCatching {
            DocumentBuilderFactory.newInstance().apply {
                // The XML comes off a device; disable external entity resolution.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        }.getOrNull() ?: return UiTree(null, UiFramework.UNKNOWN, UiTree.TestTagSupport.NOT_APPLICABLE)

        val hierarchy = document.documentElement ?: return empty()
        val root = hierarchy.childElements().firstOrNull()?.toNode() ?: return empty()

        val all = root.asSequence().toList()
        val framework = detectFramework(all)
        return UiTree(root, framework, detectTestTagSupport(framework, all))
    }

    private fun empty() = UiTree(null, UiFramework.UNKNOWN, UiTree.TestTagSupport.NOT_APPLICABLE)

    /**
     * Compose hosts itself inside an `AndroidComposeView`, which does appear in the dump.
     * Its semantics children are reported as plain `android.view.View` nodes carrying text
     * and content descriptions, which is why class names alone cannot identify Compose
     * content — only its host can.
     */
    private fun detectFramework(nodes: List<UiNode>): UiFramework {
        val hasCompose = nodes.any {
            it.className == COMPOSE_VIEW_MARKER || it.className == COMPOSE_LEGACY_MARKER
        }
        val hasViewWidgets = nodes.any { node ->
            VIEW_CONTENT_WIDGETS.any { node.className.startsWith(it) }
        }

        return when {
            hasCompose && hasViewWidgets -> UiFramework.HYBRID
            hasCompose -> UiFramework.COMPOSE
            nodes.isNotEmpty() -> UiFramework.VIEWS
            else -> UiFramework.UNKNOWN
        }
    }

    private fun detectTestTagSupport(framework: UiFramework, nodes: List<UiNode>): UiTree.TestTagSupport =
        when (framework) {
            UiFramework.VIEWS, UiFramework.UNKNOWN -> UiTree.TestTagSupport.NOT_APPLICABLE
            else -> if (nodes.any { it.testTag != null }) {
                UiTree.TestTagSupport.AVAILABLE
            } else {
                UiTree.TestTagSupport.UNAVAILABLE
            }
        }

    private fun Element.toNode(): UiNode = UiNode(
        className = attr("class"),
        packageName = attr("package"),
        text = attr("text"),
        contentDescription = attr("content-desc"),
        resourceId = attr("resource-id"),
        bounds = parseBounds(attr("bounds")),
        clickable = flag("clickable"),
        longClickable = flag("long-clickable"),
        enabled = flag("enabled"),
        focused = flag("focused"),
        focusable = flag("focusable"),
        scrollable = flag("scrollable"),
        checkable = flag("checkable"),
        checked = flag("checked"),
        selected = flag("selected"),
        password = flag("password"),
        children = childElements().map { it.toNode() },
    )

    private fun Element.attr(name: String): String = getAttribute(name).orEmpty()

    private fun Element.flag(name: String): Boolean = getAttribute(name) == "true"

    private fun Element.childElements(): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) }
        .filter { it.nodeType == Node.ELEMENT_NODE && it.nodeName == "node" }
        .map { it as Element }

    /** `bounds="[0,66][1080,220]"`. Malformed bounds degrade to zero rather than throwing. */
    internal fun parseBounds(raw: String): UiNode.Bounds {
        val numbers = Regex("-?\\d+").findAll(raw).map { it.value.toInt() }.toList()
        return if (numbers.size >= BOUNDS_VALUES) {
            UiNode.Bounds(numbers[B_LEFT], numbers[B_TOP], numbers[B_RIGHT], numbers[B_BOTTOM])
        } else {
            UiNode.Bounds(0, 0, 0, 0)
        }
    }

    private const val BOUNDS_VALUES = 4
    private const val B_LEFT = 0
    private const val B_TOP = 1
    private const val B_RIGHT = 2
    private const val B_BOTTOM = 3
}
