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

        fun string(name: String, description: String, required: Boolean = false) =
            property(name, "string", description, required)

        fun integer(name: String, description: String, required: Boolean = false) =
            property(name, "integer", description, required)

        fun boolean(name: String, description: String, required: Boolean = false) =
            property(name, "boolean", description, required)

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

        private fun property(name: String, type: String, description: String, isRequired: Boolean) {
            properties.add(
                name,
                JsonObject().apply {
                    addProperty("type", type)
                    addProperty("description", description)
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

fun JsonObject.optionalInt(name: String, default: Int): Int =
    get(name)?.takeIf { !it.isJsonNull }?.asInt ?: default

fun JsonObject.optionalBoolean(name: String, default: Boolean): Boolean =
    get(name)?.takeIf { !it.isJsonNull }?.asBoolean ?: default
