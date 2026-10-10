package spock.adb.flutter.navigation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * [VmObjects] answered from a fixture in `src/test/resources/flutter/navigator`: `isolate`, then
 * `objects` by id (`getObject`), then `instances` by class id (`getInstances`). Records each call.
 */
class FixtureObjects(val fixture: JsonObject) : VmObjects {

    val calls = mutableListOf<String>()

    override fun isolate(): JsonObject {
        calls += "getIsolate"
        return fixture.getAsJsonObject("isolate").deepCopy()
    }

    override fun get(objectId: String): JsonObject {
        calls += "getObject $objectId"
        return objects()[objectId]?.asJsonObject?.deepCopy() ?: sentinel()
    }

    override fun instances(classId: String, limit: Int): JsonObject {
        calls += "getInstances $classId"
        val all = fixture.getAsJsonObject("instances").getAsJsonObject(classId)?.deepCopy()
            ?: JsonObject().apply {
                addProperty("totalCount", 0)
                add("instances", JsonArray())
            }
        val refs = all.getAsJsonArray("instances")
        return all.apply { add("instances", JsonArray().apply { refs.take(limit).forEach(::add) }) }
    }

    fun objects(): JsonObject = fixture.getAsJsonObject("objects")

    /** The ids of the navigators the key registry holds, in its order. */
    fun keyedNavigatorIds(): List<String> = objects().getAsJsonObject(REGISTRY).getAsJsonArray("associations")
        .map { it.asJsonObject.getAsJsonObject("value") }
        .filter { className(it) == "StatefulElement" }
        .map { objects().getAsJsonObject(it.get("id").asString) }
        .mapNotNull { element ->
            element.getAsJsonArray("fields").map { it.asJsonObject }
                .first { it.getAsJsonObject("decl").get("name").asString == "_state" }
                .getAsJsonObject("value").takeIf { className(it) == "NavigatorState" }?.get("id")?.asString
        }

    /** The value of [objectId]'s field [name], as a reference. */
    fun ref(objectId: String, name: String): JsonObject = field(objectId, name).getAsJsonObject("value")

    fun field(objectId: String, name: String): JsonObject = boundFields(objectId)
        .first { it.asJsonObject.getAsJsonObject("decl").get("name").asString == name }.asJsonObject

    fun removeField(objectId: String, name: String) {
        boundFields(objectId).removeAll { it.asJsonObject.getAsJsonObject("decl").get("name").asString == name }
    }

    fun setField(objectId: String, name: String, value: JsonObject) = field(objectId, name).add("value", value)

    private fun boundFields(objectId: String): JsonArray = objects().getAsJsonObject(objectId).getAsJsonArray("fields")

    companion object {
        const val REGISTRY = "objects/3"

        private fun className(ref: JsonObject): String? = ref.getAsJsonObject("class")?.get("name")?.asString

        fun load(name: String): FixtureObjects = FixtureObjects(
            JsonParser.parseString(
                FixtureObjects::class.java.getResource("/flutter/navigator/$name")!!.readText(),
            ).asJsonObject,
        )

        fun sentinel() = JsonObject().apply {
            addProperty("type", "Sentinel")
            addProperty("kind", "Expired")
            addProperty("valueAsString", "<expired>")
        }

        fun nullRef() = JsonObject().apply {
            addProperty("type", "@Instance")
            addProperty("kind", "Null")
            addProperty("id", "objects/null")
            add("valueAsString", JsonPrimitive("null"))
        }
    }
}
