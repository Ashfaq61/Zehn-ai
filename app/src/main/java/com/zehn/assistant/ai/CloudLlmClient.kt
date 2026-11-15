package com.zehn.assistant.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.zehn.assistant.data.db.ActionLogDao
import com.zehn.assistant.data.db.ActionLogEntity
import com.zehn.assistant.data.model.AiProvider
import com.zehn.assistant.security.KeystoreCryptoManager
import com.zehn.assistant.security.SafetyPolicyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

data class LlmMessage(
    val role: String,
    val content: String
)

data class LlmToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String
)

data class LlmToolCall(
    val name: String,
    val argumentsJson: String
)

sealed class LlmStreamEvent {
    data class TextDelta(val delta: String) : LlmStreamEvent()
    data class ToolCallEvent(val call: LlmToolCall) : LlmStreamEvent()
    data class ErrorEvent(val message: String, val code: Int = 0) : LlmStreamEvent()
    data object Completed : LlmStreamEvent()
}

@Singleton
class CloudLlmClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val crypto: KeystoreCryptoManager,
    private val safetyPolicy: SafetyPolicyManager,
    private val actionLogDao: ActionLogDao
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun streamChat(
        provider: AiProvider,
        model: String,
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<LlmToolDefinition> = emptyList(),
        imageUri: Uri? = null
    ): Flow<LlmStreamEvent> = flow {
        val base64Image = imageUri?.let { encodeUriToJpegBase64(it) }

        when (provider) {
            AiProvider.GEMINI -> streamGeminiWithRotation(model, systemPrompt, messages, tools, base64Image)
                .collect { emit(it) }

            AiProvider.ANTHROPIC -> streamAnthropic(model, systemPrompt, messages, base64Image)
                .collect { emit(it) }

            AiProvider.COHERE -> callCohere(model, systemPrompt, messages)
                .collect { emit(it) }

            AiProvider.WEBHOOK -> callWebhook(messages.lastOrNull()?.content.orEmpty())
                .collect { emit(it) }

            AiProvider.OPENAI_COMPATIBLE,
            AiProvider.GROQ,
            AiProvider.OPENROUTER,
            AiProvider.TOGETHER,
            AiProvider.OLLAMA -> streamOpenAiCompatible(provider, model, systemPrompt, messages, tools, base64Image)
                .collect { emit(it) }

            AiProvider.ON_DEVICE_LITERT -> {
                emit(LlmStreamEvent.ErrorEvent("Route ON_DEVICE_LITERT to OnDeviceLiteRtManager"))
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun streamGeminiWithRotation(
        model: String,
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<LlmToolDefinition>,
        base64Image: String?
    ): Flow<LlmStreamEvent> = flow {
        val totalKeys = crypto.getGeminiKeys().size
        if (totalKeys == 0) {
            emit(LlmStreamEvent.ErrorEvent("No Gemini API key stored in Encrypted Vault.", 401))
            return@flow
        }

        var attempt = 0
        var succeeded = false
        var lastError = "Gemini request failed"
        var lastCode = 500

        while (attempt < totalKeys.coerceAtLeast(1) && !succeeded) {
            val pair = crypto.nextGeminiKey() ?: break
            val (keyIdx, rawApiKey) = pair
            val apiKey = rawApiKey.trim()
            if (apiKey.isEmpty()) {
                lastCode = 401
                lastError = "Gemini API key #${keyIdx + 1} is empty."
                attempt++
                continue
            }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"

            val payload = JSONObject().apply {
                if (systemPrompt.isNotBlank()) {
                    put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                }
                val contents = JSONArray()
                messages.forEachIndexed { idx, msg ->
                    val role = if (msg.role == "assistant") "model" else "user"
                    val parts = JSONArray().put(JSONObject().put("text", msg.content))
                    if (idx == messages.lastIndex && base64Image != null && role == "user") {
                        parts.put(
                            JSONObject().put(
                                "inline_data",
                                JSONObject().put("mime_type", "image/jpeg").put("data", base64Image)
                            )
                        )
                    }
                    contents.put(JSONObject().put("role", role).put("parts", parts))
                }
                put("contents", contents)
                if (tools.isNotEmpty()) {
                    val decls = JSONArray()
                    tools.forEach { t ->
                        decls.put(
                            JSONObject().apply {
                                put("name", t.name)
                                put("description", t.description)
                                runCatching { JSONObject(t.parametersJson) }.getOrNull()?.let {
                                    put("parameters", it)
                                }
                            }
                        )
                    }
                    put("tools", JSONArray().put(JSONObject().put("function_declarations", decls)))
                }
            }

            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            try {
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 429 || resp.code == 403) {
                        lastCode = resp.code
                        lastError = "Gemini quota/rate limit (HTTP ${resp.code}) on key #${keyIdx + 1}"
                        val rotated = crypto.markGeminiKeyQuotaLimited(keyIdx)
                        actionLogDao.insert(
                            ActionLogEntity(
                                category = "MODEL",
                                title = "Gemini Key Rotation",
                                detail = "$lastError; rotated=$rotated",
                                status = if (rotated) "INFO" else "FAILED"
                            )
                        )
                        attempt++
                        return@use
                    }
                    if (!resp.isSuccessful) {
                        lastCode = resp.code
                        lastError = "Gemini HTTP ${resp.code}: ${resp.body?.string()?.take(200)}"
                        attempt = totalKeys // non-quota failure stops loop
                        return@use
                    }

                    val body = resp.body ?: run {
                        lastError = "Empty Gemini response stream"
                        return@use
                    }
                    val reader = BufferedReader(InputStreamReader(body.byteStream()))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (safetyPolicy.policy.value.emergencyStopped) break
                        val raw = line?.trim().orEmpty()
                        if (!raw.startsWith("data:")) continue
                        val jsonStr = raw.removePrefix("data:").trim()
                        if (jsonStr == "[DONE]" || jsonStr.isEmpty()) continue
                        runCatching {
                            val obj = JSONObject(jsonStr)
                            val candidates = obj.optJSONArray("candidates") ?: return@runCatching
                            if (candidates.length() == 0) return@runCatching
                            val parts = candidates.getJSONObject(0)
                                .optJSONObject("content")
                                ?.optJSONArray("parts") ?: return@runCatching
                            for (i in 0 until parts.length()) {
                                val p = parts.getJSONObject(i)
                                val text = p.optString("text", "")
                                if (text.isNotEmpty()) emit(LlmStreamEvent.TextDelta(text))
                                val fc = p.optJSONObject("functionCall")
                                if (fc != null) {
                                    emit(
                                        LlmStreamEvent.ToolCallEvent(
                                            LlmToolCall(
                                                name = fc.optString("name"),
                                                argumentsJson = fc.optJSONObject("args")?.toString() ?: "{}"
                                            )
                                        )
                                    )
                                }
                            }
                        }
                    }
                    succeeded = true
                    emit(LlmStreamEvent.Completed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastCode = 0
                lastError = "Gemini network error: ${e.message ?: "Request failed"}"
                attempt = totalKeys
            }
        }

        if (!succeeded) {
            emit(LlmStreamEvent.ErrorEvent(lastError, lastCode))
        }
    }

    private fun streamOpenAiCompatible(
        provider: AiProvider,
        model: String,
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<LlmToolDefinition>,
        base64Image: String?
    ): Flow<LlmStreamEvent> = flow {
        val apiKey = when (provider) {
            AiProvider.OPENAI_COMPATIBLE -> crypto.getSecret(KeystoreCryptoManager.KEY_OPENAI)
            AiProvider.GROQ -> crypto.getSecret(KeystoreCryptoManager.KEY_GROQ)
            AiProvider.OPENROUTER -> crypto.getSecret(KeystoreCryptoManager.KEY_OPENROUTER)
            AiProvider.TOGETHER -> crypto.getSecret(KeystoreCryptoManager.KEY_TOGETHER)
            AiProvider.OLLAMA -> "ollama"
            else -> ""
        }
        if (provider.requiresKey && apiKey.isBlank()) {
            emit(LlmStreamEvent.ErrorEvent("Missing API key for ${provider.displayName}", 401))
            return@flow
        }

        val baseUrl = when (provider) {
            AiProvider.OPENAI_COMPATIBLE -> crypto.getSecret(KeystoreCryptoManager.KEY_OPENAI_URL).ifBlank { provider.defaultBaseUrl }
            AiProvider.OLLAMA -> crypto.getSecret(KeystoreCryptoManager.KEY_OLLAMA_URL).ifBlank { provider.defaultBaseUrl }
            else -> provider.defaultBaseUrl
        }.trimEnd('/')

        val msgArray = JSONArray().apply {
            if (systemPrompt.isNotBlank()) {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            messages.forEachIndexed { idx, msg ->
                if (idx == messages.lastIndex && base64Image != null && msg.role == "user" && provider.supportsVision) {
                    val contentArr = JSONArray()
                        .put(JSONObject().put("type", "text").put("text", msg.content))
                        .put(
                            JSONObject().put("type", "image_url").put(
                                "image_url",
                                JSONObject().put("url", "data:image/jpeg;base64,$base64Image")
                            )
                        )
                    put(JSONObject().put("role", msg.role).put("content", contentArr))
                } else {
                    put(JSONObject().put("role", msg.role).put("content", msg.content))
                }
            }
        }

        val bodyJson = JSONObject().apply {
            put("model", model)
            put("messages", msgArray)
            put("stream", true)
            if (tools.isNotEmpty() && provider.supportsTools) {
                val toolsArr = JSONArray()
                tools.forEach { t ->
                    toolsArr.put(
                        JSONObject().put("type", "function").put(
                            "function",
                            JSONObject().apply {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", runCatching { JSONObject(t.parametersJson) }.getOrElse { JSONObject() })
                            }
                        )
                    )
                }
                put("tools", toolsArr)
            }
        }

        try {
            val reqBuilder = Request.Builder()
                .url("$baseUrl/chat/completions")
                .post(bodyJson.toString().toRequestBody(jsonMediaType))
            if (apiKey.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(LlmStreamEvent.ErrorEvent("${provider.displayName} HTTP ${resp.code}: ${resp.body?.string()?.take(180)}", resp.code))
                    return@use
                }
                val body = resp.body ?: run {
                    emit(LlmStreamEvent.ErrorEvent("Empty stream"))
                    return@use
                }
                val reader = BufferedReader(InputStreamReader(body.byteStream()))
                var pendingToolName = ""
                val pendingToolArgs = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (safetyPolicy.policy.value.emergencyStopped) break
                    val raw = line?.trim().orEmpty()
                    if (!raw.startsWith("data:")) continue
                    val data = raw.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    runCatching {
                        val obj = JSONObject(data)
                        val choices = obj.optJSONArray("choices") ?: return@runCatching
                        if (choices.length() == 0) return@runCatching
                        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return@runCatching
                        val text = delta.optString("content", "")
                        if (text.isNotEmpty()) emit(LlmStreamEvent.TextDelta(text))
                        val tcs = delta.optJSONArray("tool_calls")
                        if (tcs != null && tcs.length() > 0) {
                            val fn = tcs.getJSONObject(0).optJSONObject("function")
                            if (fn != null) {
                                val n = fn.optString("name", "")
                                if (n.isNotEmpty()) pendingToolName = n
                                val argsChunk = fn.optString("arguments", "")
                                if (argsChunk.isNotEmpty()) pendingToolArgs.append(argsChunk)
                            }
                        }
                    }
                }
                if (pendingToolName.isNotEmpty()) {
                    emit(LlmStreamEvent.ToolCallEvent(LlmToolCall(pendingToolName, pendingToolArgs.toString().ifBlank { "{}" })))
                }
                emit(LlmStreamEvent.Completed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(LlmStreamEvent.ErrorEvent("${provider.displayName} error: ${e.message ?: "Request failed"}"))
        }
    }

    private fun streamAnthropic(
        model: String,
        systemPrompt: String,
        messages: List<LlmMessage>,
        base64Image: String?
    ): Flow<LlmStreamEvent> = flow {
        val apiKey = crypto.getSecret(KeystoreCryptoManager.KEY_ANTHROPIC)
        if (apiKey.isBlank()) {
            emit(LlmStreamEvent.ErrorEvent("Missing Anthropic API key", 401))
            return@flow
        }
        val msgArr = JSONArray()
        messages.forEachIndexed { idx, m ->
            val role = if (m.role == "assistant") "assistant" else "user"
            if (idx == messages.lastIndex && base64Image != null && role == "user") {
                val arr = JSONArray()
                    .put(
                        JSONObject().put("type", "image").put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", "image/jpeg")
                                .put("data", base64Image)
                        )
                    )
                    .put(JSONObject().put("type", "text").put("text", m.content))
                msgArr.put(JSONObject().put("role", role).put("content", arr))
            } else {
                msgArr.put(JSONObject().put("role", role).put("content", m.content))
            }
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", 2048)
            put("stream", true)
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            put("messages", msgArr)
        }
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(LlmStreamEvent.ErrorEvent("Anthropic HTTP ${resp.code}: ${resp.body?.string()?.take(180)}", resp.code))
                    return@use
                }
                val body = resp.body ?: run {
                    emit(LlmStreamEvent.ErrorEvent("Empty Anthropic stream"))
                    return@use
                }
                val reader = BufferedReader(InputStreamReader(body.byteStream()))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (safetyPolicy.policy.value.emergencyStopped) break
                    val raw = line?.trim().orEmpty()
                    if (!raw.startsWith("data:")) continue
                    val data = raw.removePrefix("data:").trim()
                    runCatching {
                        val obj = JSONObject(data)
                        if (obj.optString("type") == "content_block_delta") {
                            val delta = obj.optJSONObject("delta")?.optString("text", "").orEmpty()
                            if (delta.isNotEmpty()) emit(LlmStreamEvent.TextDelta(delta))
                        }
                    }
                }
                emit(LlmStreamEvent.Completed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(LlmStreamEvent.ErrorEvent("Anthropic error: ${e.message ?: "Request failed"}"))
        }
    }

    private fun callCohere(
        model: String,
        systemPrompt: String,
        messages: List<LlmMessage>
    ): Flow<LlmStreamEvent> = flow {
        val apiKey = crypto.getSecret(KeystoreCryptoManager.KEY_COHERE).trim()
        if (apiKey.isBlank()) {
            emit(LlmStreamEvent.ErrorEvent("Missing Cohere API key", 401))
            return@flow
        }
        val msgArr = JSONArray().apply {
            if (systemPrompt.isNotBlank()) put(JSONObject().put("role", "system").put("content", systemPrompt))
            messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", msgArr)
        }
        val req = Request.Builder()
            .url("https://api.cohere.com/v2/chat")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        try {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(LlmStreamEvent.ErrorEvent("Cohere HTTP ${resp.code}", resp.code))
                    return@use
                }
                val obj = JSONObject(resp.body?.string().orEmpty())
                val content = obj.optJSONObject("message")
                    ?.optJSONArray("content")
                    ?.optJSONObject(0)
                    ?.optString("text", "")
                    .orEmpty()
                if (content.isNotEmpty()) emit(LlmStreamEvent.TextDelta(content))
                emit(LlmStreamEvent.Completed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(LlmStreamEvent.ErrorEvent("Cohere error: ${e.message ?: "Request failed"}"))
        }
    }

    private fun callWebhook(text: String): Flow<LlmStreamEvent> = flow {
        val url = crypto.getSecret(KeystoreCryptoManager.KEY_WEBHOOK_URL).trim()
        if (url.isBlank()) {
            emit(LlmStreamEvent.ErrorEvent("Webhook URL not configured in API Keys", 400))
            return@flow
        }
        val payload = JSONObject().put("text", text).put("timestamp", System.currentTimeMillis())
        try {
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(LlmStreamEvent.ErrorEvent("Webhook HTTP ${resp.code}", resp.code))
                    return@use
                }
                val raw = resp.body?.string().orEmpty()
                val reply = runCatching {
                    val obj = JSONObject(raw)
                    obj.optString("reply", obj.optString("text", raw))
                }.getOrDefault(raw)
                emit(LlmStreamEvent.TextDelta(reply))
                emit(LlmStreamEvent.Completed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(LlmStreamEvent.ErrorEvent("Webhook error: ${e.message ?: "Request failed"}"))
        }
    }

    /**
     * Generates an image via OpenAI-compatible Images API or Gemini Imagen when enabled by safety policy.
     */
    suspend fun generateImage(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        if (!safetyPolicy.policy.value.imageGenerationEnabled) {
            actionLogDao.insert(
                ActionLogEntity("SAFETY", "Image Generation Blocked", "Toggle is OFF in Safety Settings", "BLOCKED")
            )
            return@withContext Result.failure(IllegalStateException("Image Generation toggle is OFF in Safety settings."))
        }
        val openAiKey = crypto.getSecret(KeystoreCryptoManager.KEY_OPENAI).trim()
        val geminiKey = crypto.nextGeminiKey()?.second?.trim().orEmpty()
        try {
            when {
                openAiKey.isNotBlank() -> {
                    val baseUrl = crypto.getSecret(KeystoreCryptoManager.KEY_OPENAI_URL)
                        .ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
                    val payload = JSONObject()
                        .put("model", "gpt-image-1")
                        .put("prompt", prompt)
                        .put("size", "1024x1024")
                        .put("response_format", "b64_json")
                    val req = Request.Builder()
                        .url("$baseUrl/images/generations")
                        .addHeader("Authorization", "Bearer $openAiKey")
                        .post(payload.toString().toRequestBody(jsonMediaType))
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(IllegalStateException("Image API HTTP ${resp.code}"))
                        }
                        val b64 = JSONObject(resp.body?.string().orEmpty())
                            .optJSONArray("data")
                            ?.optJSONObject(0)
                            ?.optString("b64_json", "")
                            .orEmpty()
                        if (b64.isBlank()) return@withContext Result.failure(IllegalStateException("Empty image bytes"))
                        val outFile = saveBase64Image(b64)
                        Result.success(outFile.absolutePath)
                    }
                }
                geminiKey.isNotBlank() -> {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/imagen-3.0-generate-002:predict?key=$geminiKey"
                    val payload = JSONObject()
                        .put("instances", JSONArray().put(JSONObject().put("prompt", prompt)))
                        .put("parameters", JSONObject().put("sampleCount", 1))
                    val req = Request.Builder()
                        .url(url)
                        .post(payload.toString().toRequestBody(jsonMediaType))
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(IllegalStateException("Imagen HTTP ${resp.code}"))
                        }
                        val b64 = JSONObject(resp.body?.string().orEmpty())
                            .optJSONArray("predictions")
                            ?.optJSONObject(0)
                            ?.optString("bytesBase64Encoded", "")
                            .orEmpty()
                        if (b64.isBlank()) return@withContext Result.failure(IllegalStateException("No image data returned"))
                        val outFile = saveBase64Image(b64)
                        Result.success(outFile.absolutePath)
                    }
                }
                else -> Result.failure(IllegalStateException("Configure a Gemini or OpenAI API key to generate images."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun saveBase64Image(base64: String): File {
        val dir = File(context.filesDir, "generated_images").apply { mkdirs() }
        val file = File(dir, "zehn_img_${System.currentTimeMillis()}.png")
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    private fun encodeUriToJpegBase64(uri: Uri): String? {
        return runCatching {
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            val bmp = stream.use { BitmapFactory.decodeStream(it) } ?: return null
            val scaled = if (bmp.width > 1024 || bmp.height > 1024) {
                val ratio = minOf(1024f / bmp.width, 1024f / bmp.height)
                Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
            } else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }
}
