package spock.adb.mcp.tools

import com.google.gson.JsonArray
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

        /**
         * `packageName` for a tool about one app. Like [deviceSerial] it carries no description: it
         * repeats in some thirty tools, and what it defaults to ([PACKAGE_DEFAULT]) is said once, in
         * the server's `initialize` instructions, and again in each result that used the default.
         */
        fun packageName() {
            properties.add("packageName", JsonObject().apply { addProperty("type", "string") })
        }

        /**
         * A whole number. [bounds] becomes the schema's `minimum` and `maximum`; pass the same
         * range to [requiredInt] or [optionalInt], which refuse a value outside it, so the limit
         * a client is shown and the one the tool enforces are one value.
         */
        fun integer(name: String, description: String, bounds: IntRange? = null, required: Boolean = false) =
            property(name, "integer", description, required) {
                bounds?.let {
                    addProperty("minimum", it.first)
                    addProperty("maximum", it.last)
                }
            }

        fun boolean(name: String, description: String, required: Boolean = false) =
            property(name, "boolean", description, required)

        /** A number that may have a fraction, such as a scale factor. Bounded as [integer] is. */
        fun number(
            name: String,
            description: String,
            bounds: ClosedFloatingPointRange<Double>? = null,
            required: Boolean = false,
        ) = property(name, "number", description, required) {
            bounds?.let {
                addProperty("minimum", it.start)
                addProperty("maximum", it.endInclusive)
            }
        }

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

        /**
         * An object whose shape is described in words rather than declared, for an output schema
         * whose detail is versioned and documented elsewhere.
         */
        fun objectValue(name: String, description: String) = property(name, "object", description, false)

        /** An array of objects, described as [objectValue] is. */
        fun objectArray(name: String, description: String) {
            val node = JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", description)
                add("items", JsonObject().apply { addProperty("type", "object") })
            }
            properties.add(name, node)
        }

        /** An object of string values under any keys, such as a push message's data payload. */
        fun stringMap(name: String, description: String, required: Boolean = false) {
            val node = JsonObject().apply {
                addProperty("type", "object")
                addProperty("description", description)
                add("additionalProperties", JsonObject().apply { addProperty("type", "string") })
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

        /**
         * Every device-targeting tool accepts this, so it is declared in one place — with no
         * description, since it is repeated in nearly every tool `tools/list` returns and its
         * name says what it is. Which device it defaults to is said once, in android_list_devices.
         */
        fun deviceSerial() {
            properties.add("deviceSerial", JsonObject().apply { addProperty("type", "string") })
        }

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

        /** `required` is left out when empty, which JSON Schema reads the same, to keep `tools/list` small. */
        fun build(): JsonObject = JsonObject().apply {
            addProperty("type", "object")
            add("properties", properties)
            if (!required.isEmpty) add("required", required)
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
