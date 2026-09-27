package com.sandbox.app

import android.content.Context
import com.brain.conversation.BrainApiGateway
import com.brain.conversation.BrainCompletion
import com.brain.conversation.ConversationContext
import com.brain.conversation.ConversationInterpreter
import com.brain.conversation.EstruturaExtraida
import com.brain.conversation.IntentAdvisor
import com.brain.conversation.NoOpConversationInterpreter
import com.brain.conversation.OrderIntentSugerido
import com.brain.conversation.StructuredLlmParser
import com.brain.router.PapelPipeline
import com.brain.secretary.Door
import com.brain.secretary.OrderIntent
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Model artifact is kept outside the APK and verified before first use. */
object LfmModelSpec {
    const val MODEL_ID = "lfm2.5-350m-q4_k_m"
    const val FILE_NAME = "LFM2.5-350M-Q4_K_M.gguf"
    const val URL = "https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF/resolve/main/LFM2.5-350M-Q4_K_M.gguf?download=true"
    const val SHA256 = "7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4"
}

enum class LfmModelState {
    NOT_INSTALLED, DOWNLOADING, VERIFYING, READY, LOADING, LOADED, CORRUPTED, UNAVAILABLE
}

class LfmModelManager(private val context: Context) {
    private val modelFile = File(context.filesDir, "brain/models/${LfmModelSpec.FILE_NAME}")
    private val lock = Any()
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(LfmModelState.NOT_INSTALLED)
    val state: kotlinx.coroutines.flow.StateFlow<LfmModelState> = _state

    fun modelFile(): File = modelFile

    /**
     * Verifies the artifact when explicitly requested. Size/mtime metadata is informational
     * only; the cryptographic SHA is the source of truth.
     */
    fun refreshState(forceVerify: Boolean = false): LfmModelState = synchronized(lock) {
        if (!forceVerify && (_state.value == LfmModelState.READY ||
            _state.value == LfmModelState.LOADING || _state.value == LfmModelState.LOADED)) {
            return@synchronized _state.value
        }
        if (!modelFile.isFile) {
            _state.value = LfmModelState.NOT_INSTALLED
            return@synchronized _state.value
        }
        val wasLoaded = _state.value == LfmModelState.LOADED
        _state.value = LfmModelState.VERIFYING
        val valid = runCatching { sha256(modelFile) == LfmModelSpec.SHA256 }.getOrDefault(false)
        if (valid) {
            _state.value = if (wasLoaded) LfmModelState.LOADED else LfmModelState.READY
        } else {
            _state.value = LfmModelState.CORRUPTED
        }
        return@synchronized _state.value
    }

    fun isReady(): Boolean =
        _state.value in setOf(LfmModelState.READY, LfmModelState.LOADING, LfmModelState.LOADED) ||
            refreshState() in setOf(LfmModelState.READY, LfmModelState.LOADING, LfmModelState.LOADED)

    fun isLoaded(): Boolean = _state.value == LfmModelState.LOADED

    internal fun markLoading() = synchronized(lock) {
        check(modelFile.isFile) { "modelo LFM local não está instalado" }
        _state.value = LfmModelState.LOADING
    }

    internal fun markLoaded() = synchronized(lock) {
        check(modelFile.isFile) { "modelo LFM local não está instalado" }
        _state.value = LfmModelState.LOADED
    }

    internal fun markLoadFailed() = synchronized(lock) {
        _state.value = LfmModelState.UNAVAILABLE
    }

    fun ensureDownloaded(onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        synchronized(lock) {
            if (refreshState() == LfmModelState.READY) return modelFile
            modelFile.parentFile?.mkdirs()
            val partial = File(modelFile.parentFile, "${modelFile.name}.part")
            _state.value = LfmModelState.DOWNLOADING
            val connection = (URL(LfmModelSpec.URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                check(connection.responseCode in 200..299) { "download do LFM falhou: HTTP ${connection.responseCode}" }
                val total = connection.contentLengthLong
                var done = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                        output.fd.sync()
                    }
                }
                _state.value = LfmModelState.VERIFYING
                check(sha256(partial) == LfmModelSpec.SHA256) {
                    "SHA-256 do modelo LFM não confere"
                }
                replaceVerifiedModel(partial)
                _state.value = LfmModelState.READY
                return modelFile
            } catch (error: Throwable) {
                _state.value = if (modelFile.isFile) LfmModelState.CORRUPTED else LfmModelState.UNAVAILABLE
                throw error
            } finally {
                connection.disconnect()
                if (partial.exists()) partial.delete()
            }
        }
    }

    private fun replaceVerifiedModel(partial: File) {
        check(partial.isFile) { "arquivo parcial do LFM não existe" }
        val moved = runCatching {
            java.nio.file.Files.move(
                partial.toPath(),
                modelFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        }.recoverCatching {
            java.nio.file.Files.move(
                partial.toPath(),
                modelFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }.isSuccess || runCatching {
            // Some Android filesystems do not implement REPLACE_EXISTING for this path.
            // The partial has already passed SHA-256, so deleting the invalid/old target
            // is safe at this point and cannot expose an unverified model.
            if (modelFile.exists() && !modelFile.delete()) {
                throw IllegalStateException("não foi possível remover o modelo LFM anterior")
            }
            java.nio.file.Files.move(partial.toPath(), modelFile.toPath())
        }.isSuccess
        check(moved) { "não foi possível finalizar o arquivo do modelo" }
    }



    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Thin synchronous adapter required by the existing BrainApiGateway contract. */
class LocalLlmBrainApiGateway(
    context: Context,
    private val modelManager: LfmModelManager = LfmModelManager(context.applicationContext),
    private val timeoutMs: Long = 1_500L
) : BrainApiGateway {
    private val mutex = Mutex()
    private var model: LlamaModel? = null

    private fun loadModelIfNeeded(): LlamaModel = model ?: Llama.loadModel(
        modelManager.modelFile().absolutePath,
        LlamaConfig(
            contextSize = 1024,
            threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
            gpuLayers = 0,
            temperature = 0.1f,
            topP = 0.9f,
            topK = 50,
            seed = 7
        )
    ).also { model = it }

    /**
     * Loads the native model before the first user request. The Result is intentional:
     * callers must observe native-load failure instead of treating a verified file as loaded.
     */
    fun preload(timeoutMs: Long = 15_000L): Result<Unit> = runCatching {
        runBlocking {
            mutex.withLock {
                if (modelManager.isLoaded() && model != null) return@withLock
                check(modelManager.isReady()) { "modelo LFM local ainda não está disponível" }
                modelManager.markLoading()
                try {
                    withTimeout(timeoutMs) { loadModelIfNeeded() }
                    modelManager.markLoaded()
                } catch (error: Throwable) {
                    modelManager.markLoadFailed()
                    throw error
                }
            }
        }
    }

    override fun complete(prompt: String, pipeline: PapelPipeline, authorizedAccountIds: Set<String>): BrainCompletion {
        require(pipeline == PapelPipeline.CONVERSACAO) { "LFM local aceita somente CONVERSACAO" }
        require(prompt.isNotBlank())
        if (!modelManager.isLoaded()) {
            preload(10_000L).getOrThrow()
        }
        check(modelManager.isLoaded()) { "modelo LFM local ainda não foi carregado" }
        val result = runBlocking {
            withTimeout(timeoutMs) {
                mutex.withLock {
                    val loaded = loadModelIfNeeded()
                    Llama.complete(
                        loaded,
                        prompt = prompt,
                        systemPrompt = "Classifique e extraia dados. Responda somente JSON de contrato. Nunca produza uma resposta ao usuário.",
                        maxTokens = 96
                    )
                }
            }
        }
        return BrainCompletion(result.text, LfmModelSpec.MODEL_ID, "local", "on-device")
    }

    fun close() = runCatching {
        model?.let(Llama::releaseModel)
        model = null
    }
}

/** Local advisor: only the door enum and confidence are trusted; rationale is discarded. */
class LfmIntentAdvisor(private val gateway: BrainApiGateway) : IntentAdvisor {
    override fun revisarClassificacao(prompt: String, classificacaoTentativa: OrderIntent): OrderIntentSugerido =
        runCatching {
            val completion = gateway.complete(
                "Classifique a porta. Retorne somente JSON: {\"door\":\"CHAT|PROMPT|CREATE\",\"confidence\":0.0}.\n" +
                    "Tentativa: ${classificacaoTentativa.door}. Pedido: $prompt",
                PapelPipeline.CONVERSACAO,
                emptySet()
            )
            val json = StructuredLlmParser.objectFrom(completion.text)
            val door = Door.valueOf(StructuredLlmParser.requiredString(json, "door").uppercase())
            val confidence = json.optDouble("confidence", Double.NaN)
            require(!confidence.isNaN() && confidence in 0.0..1.0)
            OrderIntentSugerido(door = door, confidence = confidence)
        }.getOrElse { throw IllegalStateException("LFM local indisponível", it) }
}

/** Entity-only local interpreter. Intent/query remain deterministic and entities must be literal spans. */
class LfmEntityInterpreter(private val gateway: BrainApiGateway) : ConversationInterpreter {
    private val deterministic = NoOpConversationInterpreter

    override fun extrairEstrutura(prompt: String, context: ConversationContext): EstruturaExtraida {
        val base = deterministic.extrairEstrutura(prompt, context)
        val shouldExtractEntities = prompt.length >= 20 && ENTITY_CUES.any { cue ->
            prompt.contains(cue, ignoreCase = true)
        }
        if (!shouldExtractEntities) return base

        val entities = runCatching {
            val completion = gateway.complete(
                "Extraia entidades literalmente presentes no pedido. Retorne somente JSON: {\"entities\":{\"tipo\":\"trecho literal\"}}. Pedido: $prompt",
                PapelPipeline.CONVERSACAO,
                emptySet()
            )
            val json = StructuredLlmParser.objectFrom(completion.text)
            StructuredLlmParser.stringMap(json, "entities")
                .filterValues { it.isNotBlank() && prompt.contains(it, ignoreCase = true) }
        }.getOrDefault(emptyMap())
        return base.copy(entities = entities)
    }

    private companion object {
        val ENTITY_CUES = setOf(
            " em ", " no ", " na ", " de ", " para ", " sobre ",
            "cidade", "estado", "país", "nome", "empresa", "data",
            "dia", "hora", "local", "endereço", "endereco"
        )
    }
}
