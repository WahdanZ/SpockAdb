package spock.adb

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import spock.adb.command.PushMessage

/**
 * Push message payloads saved per project, so "order shipped" and "chat message" are one pick
 * the next time rather than a table to retype.
 *
 * Per project rather than in [AppSettingService]: a payload names one app's keys and deep links,
 * and is noise in every other project. Kept in the workspace file, which is not shared through
 * version control — a payload is a developer's test fixture, not project configuration.
 */
@Service(Service.Level.PROJECT)
@State(name = "spock-pushPayloads", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PushPayloadStore : PersistentStateComponent<PushPayloadStore.State> {

    /** Mutable and default-constructible: the XML serializer requires both. */
    class State {
        var payloads: MutableList<SavedPayload> = mutableListOf()
    }

    class SavedPayload {
        var name: String = ""
        var title: String = ""
        var body: String = ""
        var data: MutableMap<String, String> = linkedMapOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun payloads(): List<PushMessage> = state.payloads.map { saved ->
        PushMessage(
            data = LinkedHashMap(saved.data),
            title = saved.title.ifBlank { null },
            body = saved.body.ifBlank { null },
            name = saved.name,
        )
    }

    fun save(message: PushMessage) {
        state.payloads = payloadsWith(payloads(), message).mapTo(mutableListOf()) { it.toSaved() }
    }

    fun remove(name: String) {
        state.payloads.removeAll { it.name == name }
    }

    private fun PushMessage.toSaved() = SavedPayload().also {
        it.name = name
        it.title = title.orEmpty()
        it.body = body.orEmpty()
        it.data = LinkedHashMap(data)
    }

    companion object {
        fun getInstance(project: Project): PushPayloadStore = project.getService(PushPayloadStore::class.java)
    }
}

/**
 * [existing] with [message] saved under its name: saving under a name already used replaces that
 * payload in place, so editing one and saving it again does not leave the old version behind.
 */
internal fun payloadsWith(existing: List<PushMessage>, message: PushMessage): List<PushMessage> {
    val name = message.name.trim()
    require(name.isNotEmpty()) { "Name the payload to save it." }
    val saved = message.copy(name = name)
    val index = existing.indexOfFirst { it.name == name }
    return if (index < 0) existing + saved else existing.toMutableList().also { it[index] = saved }
}
