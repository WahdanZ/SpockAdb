package spock.adb.storage

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * `shared_prefs/<name>.xml`, as Android's `XmlUtils.writeMapXml` writes it:
 *
 * ```
 * <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
 * <map>
 *     <string name="token">abc</string>
 *     <boolean name="first_run" value="false" />
 *     <set name="tags">
 *         <string>a</string>
 *     </set>
 * </map>
 * ```
 *
 * The writer reproduces that shape exactly, so a file read and written back with no change
 * comes back byte for byte in the common case, and a real edit shows up in a diff as that edit
 * alone. Elements the editor does not model — `<null>`, `<int-array>`, anything unexpected —
 * are kept as the markup they were read as and written back in place.
 *
 * The limit: Android escapes control characters as `&#1;` and the like, which XML 1.0 forbids,
 * so a file holding one fails to parse and is shown read-only rather than risked.
 */
internal object SharedPrefsXml : PrefsFormat {

    override val types = listOf(
        PrefType.BOOLEAN,
        PrefType.INT,
        PrefType.LONG,
        PrefType.FLOAT,
        PrefType.STRING,
        PrefType.STRING_SET,
    )

    override fun read(bytes: ByteArray): List<PrefItem> = parse(bytes).map { entry ->
        when (entry) {
            is XmlPrefEntry.Known -> PrefItem.Typed(entry.key, entry.value)
            is XmlPrefEntry.Foreign -> PrefItem.Opaque(entry.key, "<${entry.tag}>")
        }
    }

    override fun write(original: ByteArray, changes: List<PrefChange>): ByteArray =
        SharedPrefsXmlWriter.serialize(
            parse(original).applying(
                changes,
                types,
                keyOf = { it.key },
                isEditable = { it is XmlPrefEntry.Known },
                build = { key, value, _ -> XmlPrefEntry.Known(key, value) },
            ),
        )

    private fun parse(bytes: ByteArray): List<XmlPrefEntry> {
        val root = document(bytes).documentElement
        if (root.tagName != "map") notSharedPreferences("the root element is <${root.tagName}>, not <map>.")
        return root.elements().map(::entryOf)
    }

    private fun document(bytes: ByteArray): Document = try {
        builder().parse(ByteArrayInputStream(bytes))
    } catch (e: SAXException) {
        notSharedPreferences(e.message, e)
    } catch (e: IOException) {
        notSharedPreferences(e.message, e)
    }

    private fun notSharedPreferences(reason: String?, cause: Throwable? = null): Nothing =
        throw PrefsFormatException("Not a SharedPreferences file: $reason", cause)

    private fun entryOf(element: Element): XmlPrefEntry {
        val key = element.getAttribute("name")
        val value = if (element.hasAttribute("name")) valueOf(element) else null
        return value?.let { XmlPrefEntry.Known(key, it) }
            ?: XmlPrefEntry.Foreign(key, element.tagName, SharedPrefsXmlWriter.markup(element))
    }

    /**
     * Null when the element is not one of the shapes SharedPreferences writes.
     *
     * A recognised tag carrying an attribute or a child of its own is not one of them. The
     * writer rebuilds every [XmlPrefEntry.Known] from its key and value alone, so an element
     * such as `<int name="x" value="1" custom="keep" />` would lose what it carries the first
     * time any entry in the file was edited. Kept as markup, it survives untouched.
     */
    private fun valueOf(element: Element): PrefValue? = when (val tag = element.tagName) {
        "boolean", "int", "long", "float" -> element.attributeValue()?.let { scalarOf(tag, it) }
        "string" -> element.takeIf { it.hasOnly("name") }?.let { textOf(it) }?.let { PrefValue.StringValue(it) }
        "set" -> element.takeIf { it.hasOnly("name") }?.let { stringSetOf(it) }
        else -> null
    }

    /** The `value` of an element written exactly as `<tag name="…" value="…" />`, and nothing else. */
    private fun Element.attributeValue(): String? =
        takeIf { it.hasOnly("name", "value") && it.childNodes.length == 0 }?.getAttribute("value")

    private fun scalarOf(tag: String, value: String): PrefValue? = when {
        tag == "boolean" -> value.toBooleanStrictOrNull()?.let { PrefValue.BooleanValue(it) }
        tag == "int" -> value.toIntOrNull()?.let { PrefValue.IntValue(it) }
        tag == "long" -> value.toLongOrNull()?.let { PrefValue.LongValue(it) }
        else -> runCatching { PrefValue.parse(PrefType.FLOAT, value) }.getOrNull()
    }

    /** The text of an element that holds nothing but text. */
    private fun textOf(element: Element): String? = element.takeIf { it.elements().isEmpty() }?.textContent

    private fun stringSetOf(element: Element): PrefValue? {
        val items = element.elements()
        // `<string>` inside a set carries nothing of its own; one that does is kept as markup.
        if (items.any { it.tagName != "string" || !it.hasOnly() }) return null
        val strings = items.map { textOf(it) ?: return null }
        return PrefValue.StringSetValue(strings)
    }

    private fun Element.elements(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    /**
     * A fresh parser per call, hardened because the file comes off a device where any app could
     * have written it: no DOCTYPE, so no external or recursively expanding entities.
     */
    private fun builder(): DocumentBuilder = try {
        DocumentBuilderFactory.newDefaultInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isExpandEntityReferences = false
            isXIncludeAware = false
        }.newDocumentBuilder().apply {
            // The default handler prints "[Fatal Error]" to stderr before throwing.
            setErrorHandler(THROWING)
        }
    } catch (e: ParserConfigurationException) {
        throw IllegalStateException("The XML parser could not be configured safely", e)
    }

    private val THROWING = object : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit
        override fun error(exception: SAXParseException) = throw exception
        override fun fatalError(exception: SAXParseException) = throw exception
    }
}

/** True when the element carries these attributes and no others. */
private fun Element.hasOnly(vararg allowed: String): Boolean =
    (0 until attributes.length).all { attributes.item(it).nodeName in allowed }

internal sealed interface XmlPrefEntry {
    val key: String

    data class Known(override val key: String, val value: PrefValue) : XmlPrefEntry

    /** An element the editor does not model, kept as the markup it was read as. */
    data class Foreign(override val key: String, val tag: String, val markup: String) : XmlPrefEntry
}

/** Writes entries in exactly the layout and escaping Android's `FastXmlSerializer` produces. */
internal object SharedPrefsXmlWriter {

    fun serialize(entries: List<XmlPrefEntry>): ByteArray = buildString {
        append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n")
        if (entries.isEmpty()) {
            append("<map />\n")
        } else {
            append("<map>\n")
            entries.forEach { entry ->
                append(INDENT)
                when (entry) {
                    is XmlPrefEntry.Known -> appendKnown(entry)
                    is XmlPrefEntry.Foreign -> append(entry.markup)
                }
                append('\n')
            }
            append("</map>\n")
        }
    }.toByteArray(Charsets.UTF_8)

    fun markup(element: Element): String = buildString { appendMarkup(element) }

    private fun StringBuilder.appendKnown(entry: XmlPrefEntry.Known) {
        val name = escape(entry.key)
        when (val value = entry.value) {
            is PrefValue.StringValue ->
                append("<string name=\"$name\">").append(escape(value.value)).append("</string>")
            is PrefValue.StringSetValue -> appendStringSet(name, value.values)
            else -> append("<${tagOf(value)} name=\"$name\" value=\"${escape(value.text())}\" />")
        }
    }

    private fun StringBuilder.appendStringSet(name: String, values: List<String>) {
        if (values.isEmpty()) {
            append("<set name=\"$name\" />")
            return
        }
        append("<set name=\"$name\">\n")
        values.forEach { append(INDENT + INDENT).append("<string>").append(escape(it)).append("</string>\n") }
        append(INDENT).append("</set>")
    }

    private fun tagOf(value: PrefValue): String = when (value) {
        is PrefValue.BooleanValue -> "boolean"
        is PrefValue.IntValue -> "int"
        is PrefValue.LongValue -> "long"
        is PrefValue.FloatValue -> "float"
        else -> throw IllegalArgumentException("SharedPreferences cannot store a ${value.type.label}.")
    }

    private fun StringBuilder.appendMarkup(element: Element) {
        append('<').append(element.tagName)
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            append(' ').append(attribute.nodeName).append("=\"").append(escape(attribute.nodeValue)).append('"')
        }
        val children = element.childNodes
        if (children.length == 0) {
            append(" />")
            return
        }
        append('>')
        val holdsElements = (0 until children.length).any { children.item(it).nodeType == Node.ELEMENT_NODE }
        for (index in 0 until children.length) {
            val child = children.item(index)
            when (child.nodeType) {
                Node.ELEMENT_NODE -> appendMarkup(child as Element)
                // Indentation between child elements is layout, not data: kept as it was written.
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE ->
                    append(if (holdsElements && child.nodeValue.isBlank()) child.nodeValue else escape(child.nodeValue))
                // Comments and processing instructions carry no preference data.
                else -> Unit
            }
        }
        append("</").append(element.tagName).append('>')
    }

    /** Android's escape table: `"`, `&`, `<`, `>`, and every control character. */
    private fun escape(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when {
                c == '"' -> append("&quot;")
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c < ' ' -> append("&#").append(c.code).append(';')
                else -> append(c)
            }
        }
    }

    private const val INDENT = "    "
}
