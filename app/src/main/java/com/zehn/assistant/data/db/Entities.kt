package com.zehn.assistant.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val projectId: Long? = null,
    val providerName: String = "GEMINI",
    val modelName: String = "gemini-2.5-flash",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String, // "user", "assistant", "system", "tool"
    val content: String,
    val citationsJson: String = "[]",
    val imageUri: String? = null,
    val modelUsed: String = "",
    val detectedLanguage: String = "en",
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tier: String, // WORKING, SESSION, LONG_TERM, NOTIFICATION_INTEL
    val title: String,
    val content: String,
    val priority: Int = 5, // 1..10
    val sourceApp: String = "Zehn",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val documentCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "document_chunks",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class DocumentChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val documentName: String,
    val sourceUri: String,
    val pageOrSection: Int,
    val chunkText: String,
    val embeddingCsv: String, // 64-dim normalized float vector stored in SQLite
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val slug: String,
    val name: String,
    val description: String,
    val markdownBody: String,
    val parametersSchemaJson: String = """{"type":"object","properties":{}}""",
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val kind: String, // "WORKFLOW_JSON" (AI-driven multi-app) or "FLOW_YAML" (Fixed instant replay)
    val definitionText: String,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    val runCount: Int = 0
)

@Serializable
@Entity(tableName = "triggers")
data class TriggerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val triggerType: String, // TIME, NOTIFICATION, APP_OPEN, CHARGING, BATTERY_LOW, CONNECTIVITY
    val conditionValue: String, // e.g. "08:30", "com.whatsapp", "charging_on"
    val actionPrompt: String,
    val linkedFlowId: Long? = null,
    val enabled: Boolean = true,
    val lastTriggeredAt: Long? = null
)

@Serializable
@Entity(tableName = "action_logs")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // VOICE, AGENT_FAST, AGENT_GUI, SAFETY, TOOL, MODEL
    val title: String,
    val detail: String,
    val status: String, // SUCCESS, BLOCKED, FAILED, INFO
    val timestamp: Long = System.currentTimeMillis()
) {
    @androidx.room.Ignore
    constructor(category: String, title: String, detail: String, status: String) :
        this(0L, category, title, detail, status, System.currentTimeMillis())
}
