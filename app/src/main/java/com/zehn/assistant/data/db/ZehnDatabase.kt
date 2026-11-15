package com.zehn.assistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        ProjectEntity::class,
        DocumentChunkEntity::class,
        SkillEntity::class,
        WorkflowEntity::class,
        TriggerEntity::class,
        ActionLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ZehnDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun projectRagDao(): ProjectRagDao
    abstract fun skillDao(): SkillDao
    abstract fun workflowDao(): WorkflowDao
    abstract fun triggerDao(): TriggerDao
    abstract fun actionLogDao(): ActionLogDao

    companion object {
        fun build(context: Context): ZehnDatabase {
            lateinit var db: ZehnDatabase
            db = Room.databaseBuilder(
                context.applicationContext,
                ZehnDatabase::class.java,
                "zehn_assistant.db"
            ).addCallback(object : Callback() {
                override fun onCreate(supportDb: SupportSQLiteDatabase) {
                    super.onCreate(supportDb)
                    CoroutineScope(Dispatchers.IO).launch {
                        seedInitialData(db)
                    }
                }
            }).build()
            return db
        }

        private suspend fun seedInitialData(db: ZehnDatabase) {
            val convId = db.conversationDao().insert(
                ConversationEntity(
                    title = "Welcome to Zehn AI",
                    providerName = "GEMINI",
                    modelName = "gemini-2.5-flash"
                )
            )
            db.messageDao().insert(
                MessageEntity(
                    conversationId = convId,
                    role = "assistant",
                    content = "Assalam-o-Alaikum & Welcome! I'm Zehn AI. Tap the glowing orb below to speak in English, Urdu, Hindi, or Hinglish, or attach a document to a RAG Project.",
                    modelUsed = "Zehn Core"
                )
            )

            db.skillDao().insert(
                SkillEntity(
                    slug = "morning_briefing",
                    name = "Morning Briefing",
                    description = "Summarizes battery, connectivity, local time, and top notification alerts.",
                    markdownBody = """
                        ---
                        name: morning_briefing
                        description: Read system telemetry, priority notifications, and today's plan.
                        ---
                        1. Call `get_system_status` for battery, RAM, and network.
                        2. Read `NOTIFICATION_INTEL` tier memories prioritized >= 7.
                        3. Deliver a concise 3-bullet spoken briefing in the user's detected language.
                    """.trimIndent(),
                    parametersSchemaJson = """{"type":"object","properties":{"includeWeather":{"type":"boolean"}}}""",
                    enabled = true,
                    builtIn = true
                )
            )
            db.skillDao().insert(
                SkillEntity(
                    slug = "quick_whatsapp_voice",
                    name = "Voice WhatsApp Sender",
                    description = "Prepares a WhatsApp message with mandatory confirmation gate.",
                    markdownBody = """
                        ---
                        name: quick_whatsapp_voice
                        description: Open WhatsApp chat or send message after explicit user confirmation.
                        ---
                        1. Extract contact/number and message text.
                        2. ALWAYS trigger confirmation dialog before sending.
                        3. Use fast-path WhatsApp deep link; report honestly if WhatsApp is not installed.
                    """.trimIndent(),
                    parametersSchemaJson = """{"type":"object","properties":{"phone":{"type":"string"},"message":{"type":"string"}},"required":["phone","message"]}""",
                    enabled = true,
                    builtIn = true
                )
            )

            db.workflowDao().insert(
                WorkflowEntity(
                    name = "Commute Focus Setup",
                    description = "AI-driven multi-app workflow: checks battery, sets Spotify, enables driving reply.",
                    kind = "WORKFLOW_JSON",
                    definitionText = """
                        {
                          "name": "Commute Focus Setup",
                          "steps": [
                            {"tool": "get_system_status"},
                            {"tool": "set_volume", "args": {"level": 70}},
                            {"tool": "open_app", "args": {"app": "Spotify"}}
                          ]
                        }
                    """.trimIndent()
                )
            )
            db.workflowDao().insert(
                WorkflowEntity(
                    name = "Instant Flashlight & Mute",
                    description = "Fixed YAML-style Flow: zero-LLM instant hardware replay.",
                    kind = "FLOW_YAML",
                    definitionText = """
                        name: Instant Flashlight & Mute
                        mode: instant_replay
                        steps:
                          - action: toggle_flashlight
                            state: toggle
                          - action: set_volume
                            level: 0
                    """.trimIndent()
                )
            )

            db.triggerDao().insert(
                TriggerEntity(
                    name = "Morning Briefing at 08:30",
                    triggerType = "TIME",
                    conditionValue = "08:30",
                    actionPrompt = "Run morning_briefing skill and summarize priority notifications.",
                    enabled = false
                )
            )
            db.triggerDao().insert(
                TriggerEntity(
                    name = "Charger Connected Battery Check",
                    triggerType = "CHARGING",
                    conditionValue = "POWER_CONNECTED",
                    actionPrompt = "Announce battery percentage and estimated charge state.",
                    enabled = false
                )
            )

            db.memoryDao().insert(
                MemoryEntity(
                    tier = "LONG_TERM",
                    title = "Preferred Tone",
                    content = "Concise, honest, step-by-step explanations; never assume unverified actions.",
                    priority = 9
                )
            )

            db.projectRagDao().insertProject(
                ProjectEntity(
                    name = "General Knowledge Vault",
                    description = "Attach PDFs, notes, or OCR scans here for cited on-device RAG answers."
                )
            )
        }
    }
}
