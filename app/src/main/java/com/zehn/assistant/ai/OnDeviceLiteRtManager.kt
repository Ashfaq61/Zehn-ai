package com.zehn.assistant.ai

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.zehn.assistant.data.db.ActionLogDao
import com.zehn.assistant.data.db.ActionLogEntity
import com.zehn.assistant.data.model.LiteRtBackend
import com.zehn.assistant.security.KeystoreCryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

data class OnDeviceModelState(
    val totalRamMb: Long = 0L,
    val availableStorageMb: Long = 0L,
    val isRamSufficientForGemma4: Boolean = false,
    val downloadedModels: Set<String> = emptySet(),
    val activeModelId: String? = null,
    val activeBackendLabel: String = "Idle",
    val engineReady: Boolean = false,
    val busy: Boolean = false,
    val downloadingModelId: String? = null,
    val downloadProgress: Float = 0f,
    val downloadStatusText: String = "",
    val measuredTokensPerSec: Float = 0f,
    val lastError: String? = null
)

@Singleton
class OnDeviceLiteRtManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val crypto: KeystoreCryptoManager,
    private val actionLogDao: ActionLogDao
) {
    private val singleExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + singleExecutor.asCoroutineDispatcher())
    private val mutex = Mutex()

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var downloadJob: Job? = null

    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    private val _state = MutableStateFlow(OnDeviceModelState())
    val state: StateFlow<OnDeviceModelState> = _state.asStateFlow()

    init {
        refreshDeviceCapabilities()
    }

    fun refreshDeviceCapabilities() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / (1024L * 1024L)

        val stat = StatFs(context.filesDir.absolutePath)
        val availMb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024L * 1024L)

        val downloaded = ProviderCatalog.liteRtModels.filter { spec ->
            val file = File(modelsDir, spec.fileName)
            file.exists() && file.length() > 1_000_000L
        }.map { it.id }.toSet()

        _state.update {
            it.copy(
                totalRamMb = totalRamMb,
                availableStorageMb = availMb,
                isRamSufficientForGemma4 = totalRamMb >= 3500L,
                downloadedModels = downloaded
            )
        }
    }

    fun modelFileFor(spec: LiteRtModelSpec): File = File(modelsDir, spec.fileName)

    fun isModelEligibleForRam(spec: LiteRtModelSpec): Boolean {
        return _state.value.totalRamMb >= spec.minRamMb
    }

    fun startResumableDownload(spec: LiteRtModelSpec) {
        if (downloadJob?.isActive == true) return
        refreshDeviceCapabilities()
        val requiredMb = spec.expectedSizeBytes / (1024L * 1024L)
        if (_state.value.availableStorageMb < requiredMb + 250L) {
            _state.update {
                it.copy(
                    lastError = "Insufficient storage: need ${requiredMb + 250} MB free, only ${it.availableStorageMb} MB available."
                )
            }
            return
        }

        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            val partFile = File(modelsDir, "${spec.fileName}.part")
            val finalFile = modelFileFor(spec)
            var existingBytes = if (partFile.exists()) partFile.length() else 0L

            _state.update {
                it.copy(
                    downloadingModelId = spec.id,
                    downloadProgress = 0f,
                    downloadStatusText = "Connecting to Hugging Face (${spec.name})...",
                    lastError = null
                )
            }

            try {
                val reqBuilder = Request.Builder().url(spec.downloadUrl)
                val hfToken = crypto.getSecret(KeystoreCryptoManager.KEY_HF_TOKEN)
                if (hfToken.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer $hfToken")
                }
                if (existingBytes > 0L) {
                    reqBuilder.addHeader("Range", "bytes=$existingBytes-")
                }

                okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) {
                        throw IllegalStateException("Hugging Face download HTTP ${resp.code}")
                    }
                    val append = resp.code == 206 && existingBytes > 0L
                    if (!append) existingBytes = 0L
                    val body = resp.body ?: throw IllegalStateException("Empty download body")
                    val contentLen = body.contentLength()
                    val totalBytes = if (contentLen > 0) existingBytes + contentLen else spec.expectedSizeBytes

                    FileOutputStream(partFile, append).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var copied = existingBytes
                        val stream = body.byteStream()
                        var read: Int
                        while (stream.read(buf).also { read = it } != -1) {
                            fos.write(buf, 0, read)
                            copied += read
                            val progress = (copied.toFloat() / totalBytes.coerceAtLeast(1L)).coerceIn(0f, 1f)
                            val mbDone = copied / (1024L * 1024L)
                            val mbTotal = totalBytes / (1024L * 1024L)
                            _state.update {
                                it.copy(
                                    downloadProgress = progress,
                                    downloadStatusText = "Downloading ${spec.name}: $mbDone / $mbTotal MB"
                                )
                            }
                        }
                    }
                }
                if (partFile.renameTo(finalFile)) {
                    actionLogDao.insert(
                        ActionLogEntity("MODEL", "Downloaded ${spec.name}", finalFile.absolutePath, "SUCCESS")
                    )
                }
                refreshDeviceCapabilities()
                _state.update {
                    it.copy(
                        downloadingModelId = null,
                        downloadProgress = 1f,
                        downloadStatusText = "${spec.name} ready offline"
                    )
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(downloadingModelId = null, downloadStatusText = "Download paused (resumable)")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        downloadingModelId = null,
                        downloadStatusText = "Download paused",
                        lastError = e.message ?: "Download failed"
                    )
                }
            }
        }
    }

    fun pauseDownload() {
        downloadJob?.cancel()
    }

    fun deleteModel(spec: LiteRtModelSpec) {
        scope.launch {
            mutex.withLock {
                if (_state.value.activeModelId == spec.id) releaseInternal()
                modelFileFor(spec).delete()
                File(modelsDir, "${spec.fileName}.part").delete()
                refreshDeviceCapabilities()
            }
        }
    }

    suspend fun loadModel(spec: LiteRtModelSpec, backendPref: LiteRtBackend, systemPrompt: String): Boolean =
        withContext(singleExecutor.asCoroutineDispatcher()) {
            mutex.withLock {
                val file = modelFileFor(spec)
                if (!file.exists()) {
                    _state.update { it.copy(lastError = "Model ${spec.name} is not downloaded yet.") }
                    return@withLock false
                }
                if (!isModelEligibleForRam(spec)) {
                    _state.update {
                        it.copy(lastError = "${spec.name} requires ${spec.minRamMb} MB RAM (device has ${it.totalRamMb} MB).")
                    }
                    return@withLock false
                }
                if (engine?.engineConfig?.modelPath == file.absolutePath && _state.value.engineReady) {
                    return@withLock true
                }
                releaseInternal()

                val chosenBackend = when (backendPref) {
                    LiteRtBackend.GPU -> Backend.GPU()
                    LiteRtBackend.CPU, LiteRtBackend.NPU -> Backend.CPU()
                    LiteRtBackend.AUTO -> if (_state.value.totalRamMb >= 5500L) Backend.GPU() else Backend.CPU()
                }
                val backendLabel = if (chosenBackend is Backend.GPU) "GPU" else "CPU"

                return@withLock try {
                    val config = EngineConfig(
                        modelPath = file.absolutePath,
                        backend = chosenBackend,
                        cacheDir = context.cacheDir.path
                    )
                    val newEngine = Engine(config).also { it.initialize() }
                    engine = newEngine
                    conversation = newEngine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(systemPrompt),
                            samplerConfig = SamplerConfig(topK = 16, topP = 0.92, temperature = 0.7)
                        )
                    )
                    _state.update {
                        it.copy(
                            engineReady = true,
                            activeModelId = spec.id,
                            activeBackendLabel = backendLabel,
                            lastError = null
                        )
                    }
                    true
                } catch (e: Exception) {
                    // Fallback to CPU if GPU OpenCL initialization fails
                    if (chosenBackend is Backend.GPU) {
                        return@withLock try {
                            releaseInternal()
                            val cpuConfig = EngineConfig(
                                modelPath = file.absolutePath,
                                backend = Backend.CPU(),
                                cacheDir = context.cacheDir.path
                            )
                            val cpuEngine = Engine(cpuConfig).also { it.initialize() }
                            engine = cpuEngine
                            conversation = cpuEngine.createConversation(
                                ConversationConfig(
                                    systemInstruction = Contents.of(systemPrompt),
                                    samplerConfig = SamplerConfig(topK = 16, topP = 0.92, temperature = 0.7)
                                )
                            )
                            _state.update {
                                it.copy(
                                    engineReady = true,
                                    activeModelId = spec.id,
                                    activeBackendLabel = "CPU (Auto-Fallback)",
                                    lastError = null
                                )
                            }
                            true
                        } catch (cpuEx: Exception) {
                            releaseInternal()
                            _state.update { it.copy(lastError = cpuEx.message) }
                            false
                        }
                    } else {
                        releaseInternal()
                        _state.update { it.copy(lastError = e.message) }
                        false
                    }
                }
            }
        }

    fun streamPrompt(prompt: String): Flow<LlmStreamEvent> = flow {
        val conv = conversation
        if (!_state.value.engineReady || conv == null) {
            emit(LlmStreamEvent.ErrorEvent("LiteRT-LM model is not loaded. Download and load Gemma 4 E2B or Qwen3 first."))
            return@flow
        }
        if (_state.value.busy) {
            emit(LlmStreamEvent.ErrorEvent("On-device engine is busy"))
            return@flow
        }
        _state.update { it.copy(busy = true, lastError = null) }
        val startMs = System.currentTimeMillis()
        var tokenCount = 0
        try {
            conv.sendMessageAsync(prompt).collect { chunk ->
                val text = chunk.toString()
                if (text.isNotEmpty()) {
                    tokenCount++
                    emit(LlmStreamEvent.TextDelta(text))
                }
            }
            val elapsedSec = ((System.currentTimeMillis() - startMs).coerceAtLeast(1L)) / 1000f
            val tps = tokenCount / elapsedSec
            _state.update { it.copy(measuredTokensPerSec = tps) }
            emit(LlmStreamEvent.Completed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(lastError = e.message) }
            emit(LlmStreamEvent.ErrorEvent(e.message ?: "On-device inference error"))
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }.flowOn(singleExecutor.asCoroutineDispatcher())

    fun cancelActiveGeneration() {
        scope.launch {
            conversation?.cancelProcess()
        }
    }

    suspend fun runAutoTuner(): String = withContext(Dispatchers.IO) {
        refreshDeviceCapabilities()
        val ram = _state.value.totalRamMb
        val recommendation = when {
            ram >= 7200L -> "Flagship RAM ($ram MB): Gemma 4 E4B/E2B with GPU OpenCL recommended."
            ram >= 3500L -> "Mid-tier RAM ($ram MB): Gemma 4 E2B Q4 with Adaptive CPU/GPU recommended."
            else -> "Low RAM ($ram MB): Use Qwen3 0.6B on CPU or Hybrid Cloud mode to prevent OOM."
        }
        actionLogDao.insert(ActionLogEntity("MODEL", "LiteRT Auto-Tuner", recommendation, "INFO"))
        recommendation
    }

    private suspend fun releaseInternal() {
        _state.update { it.copy(engineReady = false) }
        while (_state.value.busy) {
            conversation?.cancelProcess()
            delay(100)
        }
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        _state.update { it.copy(activeModelId = null, activeBackendLabel = "Idle") }
    }
}
