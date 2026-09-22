package spock.adb.mcp.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Small builder for the JSON Schema that MCP clients use to type tool arguments.
 *
 * Hand-written schema literals drift from the code that reads the arguments; this keeps the
 * declaration next to the parsing and makes a missing required field a compile-time concern
 * rather than a runtime surprise for the agent.
 */
object Schema {

    fun obj(block: ObjectBuilder.() -> Unit): JsonObject = ObjectBuilder().apply(block).build()

    /** Schema for a tool that takes no arguments. */
    fun empty(): JsonObject = obj { }

    class ObjectBuilder {
        private val properties = JsonObject()
        private val required = JsonArray()

        /**
         * With [mayBeEmpty], an empty string is a value rather than an omission — declared as
         * `minLength: 0`, which is both what JSON Schema says and what the dispatcher reads
         * before it refuses a call for a missing argument.
         */
        fun string(name: String, description: String, required: Boolean = false, mayBeEmpty: Boolean = false) =
            property(name, "string", description, required) {
                if (mayBeEmpty) addProperty("minLength", 0)
            }

        fun integer(name: String, description: String, required: Boolean = false) =
            property(name, "integer", description, required)

        fun boolean(name: String, description: String, required: Boolean = false) =
            property(name, "boolean", description, required)

        /**
     * An array of strings, optionally constrained to a fixed set.
     *
     * Declared here rather than hand-written per tool so the "which sections do you want"
     * shape used by android_get_debug_context stays consistent with the rest of the schema.
     */
        fun stringArray(
            name: String,
            description: String,
            values: List<String>? = null,
            required: Boolean = false,
        ) {
            val items = JsonObject().apply {
                addProperty("type", "string")
                values?.let { allowed -> add("enum", JsonArray().also { node -> allowed.forEach(node::add) }) }
            }
            val node = JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", description)
                add("items", items)
            }
            properties.add(name, node)
            if (required) this.required.add(name)
        }

        fun enumeration(name: String, description: String, values: List<String>, required: Boolean = false) {
            val node = JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", description)
                add("enum", JsonArray().also { values.forEach(it::add) })
            }
            properties.add(name, node)
            if (required) this.required.add(name)
        }

        /** Every device-targeting tool accepts this, so it is declared in one place. */
        fun deviceSerial() = string(
            "deviceSerial",
            "Serial of the target device. Defaults to the device selected with " +
                "android_select_device, or the only attached device.",
        )

        private fun property(
            name: String,
            type: String,
            description: String,
            isRequired: Boolean,
            extras: JsonObject.() -> Unit = {},
        ) {
            properties.add(
                name,
                JsonObject().apply {
                    addProperty("type", type)
                    addProperty("description", description)
                    extras()
                },
            )
            if (isRequired) required.add(name)
        }

        fun build(): JsonObject = JsonObject().apply {
            addProperty("type", "object")
            add("properties", properties)
            add("required", required)
        }
    }
}

/** Argument accessors that fail with a message an agent can act on. */
fun JsonObject.optionalString(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

fun JsonObject.requiredString(name: String): String =
    optionalString(name) ?: throw IllegalArgumentException("Missing required argument '$name'")

/**
 * A required string argument that may be empty, for a field where "" is a value: a preference
 * key, which both storage formats allow, is one. Only an absent or null argument is missing.
 */
fun JsonObject.requiredText(name: String): String {
    val element = get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
        ?: throw IllegalArgumentException("Missing required argument '$name'")
    return element.asString
}

/**
 * A required whole-number argument.
 *
 * Lived in InteractionTools while tap and swipe were the only tools taking coordinates;
 * moved here next to the other accessors once the proxy tools needed a port.
 *
 * @throws IllegalArgumentException when absent, or when the value is not a whole number; see
 *   [asStrictInt].
 */
fun JsonObject.requiredInt(name: String): Int {
    val element = get(name)?.takeIf { !it.isJsonNull }
        ?: throw IllegalArgumentException("Missing required argument '$name'")
    return element.asStrictInt(name)
}

/**
 * An optional whole-number argument with a default. A value that is present is held to the
 * same rules as [requiredInt] — sending one that is wrong is not the same as leaving it out.
 */
fun JsonObject.optionalInt(name: String, default: Int): Int = optionalInt(name) ?: default

/** As [optionalInt], but null when omitted, for a tool whose fallback is not a constant. */
fun JsonObject.optionalInt(name: String): Int? =
    get(name)?.takeIf { !it.isJsonNull }?.asStrictInt(name)

/**
 * The value as an Int, or an error naming [name] and what is wrong with it.
 *
 * Gson's `asInt` truncates, so a port of `8888.9` quietly became 8888 and `"8888"` was parsed
 * out of a string, neither of which an agent that sent them would have meant. A number with a
 * zero fractional part such as `8888.0` is accepted, because JSON Schema's `integer` accepts
 * it too; one with a real fraction, or outside Int's range, is refused. Read as a BigDecimal
 * rather than a Double so a large value is not rounded or clamped into something that looks
 * valid before it is checked.
 */
private fun JsonElement.asStrictInt(name: String): Int {
    require(isJsonPrimitive) {
        "Argument '$name' must be a whole number, not ${if (isJsonArray) "an array" else "an object"}."
    }
    val primitive = asJsonPrimitive
    require(primitive.isNumber) { "Argument '$name' must be a whole number, got $primitive." }

    val number = primitive.asBigDecimal.stripTrailingZeros()
    require(number.scale() <= 0) {
        "Argument '$name' must be a whole number, got ${primitive.asString}."
    }
    return try {
        number.intValueExact()
    } catch (e: ArithmeticException) {
        throw IllegalArgumentException("Argument '$name' is out of range: ${primitive.asString}.", e)
    }
}

/**
 * A required true/false argument.
 *
 * Held to the same standard as [requiredInt]: a missing flag is an error rather than a silent
 * false, because "leave this charger alone" and "disconnect it" are different instructions.
 *
 * @throws IllegalArgumentException when absent or not a boolean.
 */
fun JsonObject.requiredBoolean(name: String): Boolean {
    val element = get(name)?.takeIf { !it.isJsonNull }
        ?: throw IllegalArgumentException("Missing required argument '$name'")
    require(element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) {
        "Argument '$name' must be true or false, got $element."
    }
    return element.asBoolean
}

fun JsonObject.optionalBoolean(name: String, default: Boolean): Boolean =
    get(name)?.takeIf { !it.isJsonNull }?.asBoolean ?: default

/**
 * A string array argument, tolerating the single bare string clients sometimes send where an
 * array is declared. Returns null when the caller said nothing, so a tool can tell "omitted"
 * from "explicitly empty".
 */
fun JsonObject.optionalStringList(name: String): List<String>? {
    val element = get(name)?.takeIf { !it.isJsonNull } ?: return null
    if (element.isJsonPrimitive) return listOf(element.asString)
    if (!element.isJsonArray) return null
    return element.asJsonArray.mapNotNull { it.takeIf { item -> !item.isJsonNull }?.asString }
}
