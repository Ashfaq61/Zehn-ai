package com.zehn.assistant.data

import com.zehn.assistant.data.db.ConversationEntity
import com.zehn.assistant.data.db.MemoryEntity
import com.zehn.assistant.data.db.MessageEntity
import com.zehn.assistant.data.db.SkillEntity
import com.zehn.assistant.data.db.TriggerEntity
import com.zehn.assistant.data.db.WorkflowEntity
import com.zehn.assistant.data.db.ZehnDatabase
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ZehnBackupPayload(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: ZehnSettings,
    val memories: List<MemoryEntity>,
    val skills: List<SkillEntity>,
    val workflows: List<WorkflowEntity>,
    val triggers: List<TriggerEntity>,
    val conversations: List<ConversationEntity>,
    val messages: List<MessageEntity>
)

@Singleton
class BackupManager @Inject constructor(
    private val db: ZehnDatabase,
    private val prefs: PreferencesRepository,
    private val json: Json
) {
    suspend fun exportAllToJson(): String {
        val payload = ZehnBackupPayload(
            settings = prefs.settings.first(),
            memories = db.memoryDao().getAllOnce(),
            skills = db.skillDao().getAllOnce(),
            workflows = db.workflowDao().getAllOnce(),
            triggers = db.triggerDao().getAllOnce(),
            conversations = db.conversationDao().getAllOnce(),
            messages = db.messageDao().getAllOnce()
        )
        return json.encodeToString(payload)
    }

    suspend fun importFromJson(rawJson: String): Result<Int> = runCatching {
        val payload = json.decodeFromString<ZehnBackupPayload>(rawJson)
        prefs.updateSettings { payload.settings }
        payload.memories.forEach { db.memoryDao().insert(it.copy(id = 0)) }
        payload.skills.forEach { db.skillDao().insert(it.copy(id = 0)) }
        payload.workflows.forEach { db.workflowDao().insert(it.copy(id = 0)) }
        payload.triggers.forEach { db.triggerDao().insert(it.copy(id = 0)) }
        val convMap = mutableMapOf<Long, Long>()
        payload.conversations.forEach { conv ->
            val newId = db.conversationDao().insert(conv.copy(id = 0))
            convMap[conv.id] = newId
        }
        payload.messages.forEach { msg ->
            val targetConv = convMap[msg.conversationId] ?: return@forEach
            db.messageDao().insert(msg.copy(id = 0, conversationId = targetConv))
        }
        payload.conversations.size + payload.memories.size + payload.skills.size
    }
}
