package com.sandbox.app

import android.content.Context
import android.util.Log
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
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val LFM_DIAG_TAG = "BrainCode.LFM.Diag"

private fun logLfmDiagnostic(stage: String, startMs: Long, details: String = "") {
    val endMs = System.nanoTime() / 1_000_000L
    val suffix = if (details.isBlank()) "" else " $details"
    Log.d(LFM_DIAG_TAG, "$stage start=$startMs end=$endMs duration_ms=${endMs - startMs}$suffix")
}

/** Model artifact is kept outside the APK and verified before first use. */
object LfmModelSpec {
    const val MODEL_ID = "lfm2.5-350m-q4_k_m"
    const val FILE_NAME = "LFM2.5-350M-Q4_K_M.gguf"
    const val URL = "https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF/resolve/main/LFM2.5-350M-Q4_K_M.gguf?download=true"
    const val SHA256 = "7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4"
}

enum class LfmModelState {
    NOT_INSTALLED, DOWNLOADING, VERIFYING, READY, LOADING, LOADED, CORRUPTED, LOAD_FAILED, UNAVAILABLE
}

class LfmModelManager(private val context: Context) {
    private val modelFile = File(context.filesDir, "brain/models/${LfmModelSpec.FILE_NAME}")
    private val lock = Any()
    private val downloadLock = java.util.concurrent.locks.ReentrantLock()
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(LfmModelState.NOT_INSTALLED)
    private var verifiedSha256: String? = null
    private var verifiedSize: Long = -1L
    private var verifiedMtime: Long = -1L
    val state: kotlinx.coroutines.flow.StateFlow<LfmModelState> = _state

    /** bytesDownloaded to totalBytes (-1 if unknown). Observed by the settings UI. */
    private val _downloadProgress = kotlinx.coroutines.flow.MutableStateFlow(0L to -1L)
    val downloadProgress: kotlinx.coroutines.flow.StateFlow<Pair<Long, Long>> = _downloadProgress

    fun modelFile(): File = modelFile

    /**
     * Verifies the artifact when explicitly requested. Size/mtime metadata is informational
     * only; the cryptographic SHA is the source of truth.
     */
    fun refreshState(forceVerify: Boolean = false): LfmModelState {
        val started = System.nanoTime() / 1_000_000L
        try {
            synchronized(lock) {
                if (_state.value == LfmModelState.LOADED) {
                    check(!forceVerify) { "não é permitido verificar forçadamente o GGUF enquanto o modelo nativo está carregado" }
                    return if (!artifactChangedSinceVerification()) LfmModelState.LOADED else LfmModelState.LOAD_FAILED
                }
                if (!forceVerify && (_state.value == LfmModelState.READY || _state.value == LfmModelState.LOADING)) return _state.value
                if (!modelFile.isFile) {
                    _state.value = LfmModelState.NOT_INSTALLED
                    return _state.value
                }
                _state.value = LfmModelState.VERIFYING
            }
            val digest = runCatching { sha256(modelFile) }.getOrNull()
            synchronized(lock) {
                if (digest == LfmModelSpec.SHA256 && modelFile.isFile) {
                    verifiedSha256 = digest
                    verifiedSize = modelFile.length()
                    verifiedMtime = modelFile.lastModified()
                    _state.value = LfmModelState.READY
                } else {
                    verifiedSha256 = null
                    _state.value = LfmModelState.CORRUPTED
                }
                return _state.value
            }
        } finally {
            if (forceVerify) logLfmDiagnostic("refreshState", started, "forceVerify=true")
        }
    }

    fun isReady(): Boolean = synchronized(lock) {
        _state.value in setOf(LfmModelState.READY, LfmModelState.LOADING, LfmModelState.LOADED)
    }

    fun isLoaded(): Boolean = _state.value == LfmModelState.LOADED

    internal fun verifiedSha256(): String? = synchronized(lock) { verifiedSha256 }

    internal fun artifactChangedSinceVerification(): Boolean = synchronized(lock) {
        val started = System.nanoTime() / 1_000_000L
        val exists = modelFile.isFile
        val length = modelFile.length()
        val mtime = modelFile.lastModified()
        val changed = !exists || length != verifiedSize || mtime != verifiedMtime
        val details = if (changed) "result=true length=$length verifiedSize=$verifiedSize mtime=$mtime verifiedMtime=$verifiedMtime" else "result=false"
        logLfmDiagnostic("artifactChangedSinceVerification", started, details)
        changed
    }

    internal fun markLoading() = synchronized(lock) {
        check(modelFile.isFile) { "modelo LFM local não está instalado" }
        check(verifiedSha256 == LfmModelSpec.SHA256) { "modelo LFM local não foi verificado" }
        check(!artifactChangedSinceVerification()) { "GGUF LFM mudou após a verificação" }
        _state.value = LfmModelState.LOADING
    }

    internal fun markLoaded() = synchronized(lock) {
        check(modelFile.isFile) { "modelo LFM local não está instalado" }
        check(verifiedSha256 == LfmModelSpec.SHA256) { "modelo LFM não foi verificado" }
        check(!artifactChangedSinceVerification()) { "GGUF LFM mudou durante o carregamento" }
        _state.value = LfmModelState.LOADED
    }

    internal fun markLoadFailed() = synchronized(lock) {
        _state.value = LfmModelState.LOAD_FAILED
    }

    internal fun prepareForReloadAfterNativeRelease() = synchronized(lock) {
        check(_state.value == LfmModelState.LOADED) { "modelo LFM não está carregado" }
        check(artifactChangedSinceVerification()) { "GGUF LFM não mudou desde a verificação" }
        verifiedSha256 = null
        verifiedSize = -1L
        verifiedMtime = -1L
        _state.value = LfmModelState.READY
    }


    fun ensureDownloaded(onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        downloadLock.lock()
        try {
            synchronized(lock) {
                if (_state.value == LfmModelState.LOADED || _state.value == LfmModelState.LOADING) {
                    throw IllegalStateException("não é permitido substituir o GGUF enquanto o modelo nativo está carregado")
                }
                if (_state.value == LfmModelState.READY && !artifactChangedSinceVerification()) return modelFile
                modelFile.parentFile?.mkdirs()
            }
            if (refreshState() == LfmModelState.READY) return modelFile

            val partial = File(modelFile.parentFile, "${modelFile.name}.part")
            synchronized(lock) { _state.value = LfmModelState.DOWNLOADING }
            // Resume from whatever bytes survived a previous attempt instead of restarting
            // from zero every time — important on slow/flaky connections.
            val resumeFrom = if (partial.isFile) partial.length() else 0L
            val connection = (URL(LfmModelSpec.URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (resumeFrom > 0L) setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            var corrupted = false
            try {
                val serverResumed = resumeFrom > 0L && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
                if (resumeFrom > 0L && !serverResumed) {
                    // Server ignored the Range header (or our partial is stale); start clean.
                    partial.delete()
                }
                check(connection.responseCode in 200..299 || connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    "download do LFM falhou: HTTP ${connection.responseCode}"
                }
                val startAt = if (serverResumed) resumeFrom else 0L
                val remaining = connection.contentLengthLong
                val total = if (remaining >= 0L) startAt + remaining else -1L
                var done = startAt
                onProgress(done, total)
                _downloadProgress.value = done to total
                connection.inputStream.use { input ->
                    FileOutputStream(partial, serverResumed).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                            _downloadProgress.value = done to total
                        }
                        output.fd.sync()
                    }
                }
                synchronized(lock) { _state.value = LfmModelState.VERIFYING }
                check(sha256(partial) == LfmModelSpec.SHA256) {
                    corrupted = true
                    "SHA-256 do modelo LFM não confere"
                }
                synchronized(lock) {
                    replaceVerifiedModel(partial)
                    verifiedSha256 = LfmModelSpec.SHA256
                    verifiedSize = modelFile.length()
                    verifiedMtime = modelFile.lastModified()
                    _state.value = LfmModelState.READY
                }
                _downloadProgress.value = 0L to -1L
                return modelFile
            } catch (error: Throwable) {
                synchronized(lock) {
                    _state.value = if (modelFile.isFile) LfmModelState.CORRUPTED else LfmModelState.NOT_INSTALLED
                }
                // Only drop the partial file when we know its bytes are actually wrong
                // (bad checksum). A network hiccup should not throw away real progress —
                // that is what was making downloads restart from 0% forever on slow links.
                if (corrupted && partial.exists()) partial.delete()
                throw error
            } finally {
                connection.disconnect()
            }
        } finally {
            downloadLock.unlock()
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

/**
 * Serializes native inference on one dedicated thread and bounds the caller wait.
 * llama.cpp may ignore interruption after timeout; keeping the executor alive ensures a
 * timed-out native call cannot overlap the next call against the same native model.
 */
internal class LfmNativeInferenceRunner(
    private val timeoutMs: Long = 3_000L,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) : AutoCloseable {
    init { require(timeoutMs > 0L) }

    fun <T> run(block: suspend () -> T): T {
        val future = executor.submit(Callable { runBlocking { block() } })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            throw IllegalStateException("LFM local excedeu o tempo limite de $timeoutMs ms", error)
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

/** Thin synchronous adapter required by the existing BrainApiGateway contract. */
class LocalLlmBrainApiGateway(
    context: Context,
    private val modelManager: LfmModelManager = LfmModelManager(context.applicationContext),
    private val timeoutMs: Long = 3_000L
) : BrainApiGateway {
    private val mutex = Mutex()
    private val nativeRunner = LfmNativeInferenceRunner(timeoutMs)
    private var model: LlamaModel? = null
    private var loadedSha256: String? = null

    private suspend fun loadModelIfNeeded(): LlamaModel {
        model?.let { return it }
        val started = System.nanoTime() / 1_000_000L
        try {
            return Llama.loadModel(
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
        } finally {
            logLfmDiagnostic("Llama.loadModel", started)
        }
    }

    /**
     * Loads the native model before the first user request. The Result is intentional:
     * callers must observe native-load failure instead of treating a verified file as loaded.
     */
    fun preload(): Result<Unit> {
        val started = System.nanoTime() / 1_000_000L
        var nativeAction = "unknown"
        return runCatching {
            runBlocking {
                mutex.withLock {
                    if (modelManager.isLoaded() && model != null && loadedSha256 == modelManager.verifiedSha256() && !modelManager.artifactChangedSinceVerification()) {
                        nativeAction = "confirmed_cached"
                        return@withLock
                    }
                    val currentModel = model
                    if (currentModel != null) {
                        nativeAction = "reloaded"
                        Llama.releaseModel(currentModel)
                        model = null
                        loadedSha256 = null
                    } else {
                        nativeAction = "loaded_initial"
                    }
                    if (modelManager.artifactChangedSinceVerification()) {
                        if (modelManager.isLoaded()) modelManager.prepareForReloadAfterNativeRelease()
                        modelManager.refreshState(forceVerify = true)
                    }
                    check(modelManager.isReady()) { "modelo LFM local ainda não está disponível" }
                    check(modelManager.verifiedSha256() == LfmModelSpec.SHA256) { "modelo LFM local não possui artefato verificado" }
                    modelManager.markLoading()
                    try {
                        loadModelIfNeeded()
                        loadedSha256 = modelManager.verifiedSha256()
                        modelManager.markLoaded()
                    } catch (error: Throwable) {
                        runCatching { model?.let(Llama::releaseModel) }
                        model = null
                        loadedSha256 = null
                        modelManager.markLoadFailed()
                        throw error
                    }
                }
            }
        }.also { result ->
            logLfmDiagnostic("preload", started, "native_action=$nativeAction result=${if (result.isSuccess) "success" else "failure"}")
        }
    }

    override fun complete(prompt: String, pipeline: PapelPipeline, authorizedAccountIds: Set<String>): BrainCompletion {
        require(pipeline == PapelPipeline.CONVERSACAO) { "LFM local aceita somente CONVERSACAO" }
        require(prompt.isNotBlank())
        return runInference(
            prompt = prompt,
            systemPrompt = "Classifique e extraia dados. Responda somente JSON de contrato. Nunca produza uma resposta ao usuário.",
            maxTokens = 96
        )
    }

    /**
     * Camada 2 (llm) do fluxo local→llm→web→api das portas CHAT/PROMPT: gera um RASCUNHO de
     * resposta livre, no dispositivo, sem custo e sem rede. Separado de [complete] de propósito —
     * aquele é usado por IntentAdvisor/ConversationInterpreter e precisa continuar restrito a
     * JSON de classificação; este devolve texto para revisão (nunca é entregue direto ao usuário
     * por quem chama — quem decide isso é o revisor determinístico/LLM do chamador).
     */
    fun completeDraft(prompt: String): BrainCompletion {
        require(prompt.isNotBlank())
        return runInference(
            prompt = prompt,
            systemPrompt = "Você é o implementador de rascunhos conversacionais do BrainCode, rodando" +
                " localmente no dispositivo. Seu texto NUNCA é entregue diretamente ao usuário — outra" +
                " etapa revisa e decide se ele vira a resposta final. Escreva apenas o rascunho da" +
                " resposta, em pt-BR, sem prefácio, comentário, aspas ou menção a esta instrução. Não" +
                " invente fatos verificáveis (datas, números, eventos, nomes) sobre os quais não tenha" +
                " certeza absoluta; nesse caso, diga que não tem certeza.",
            maxTokens = 320
        )
    }

    private fun runInference(prompt: String, systemPrompt: String, maxTokens: Int): BrainCompletion {
        val started = System.nanoTime() / 1_000_000L
        try {
            if (!modelManager.isLoaded()) {
                preload().getOrThrow()
            }
            if (modelManager.artifactChangedSinceVerification() || loadedSha256 != modelManager.verifiedSha256()) {
                preload().getOrThrow()
            }
            check(modelManager.isLoaded() && loadedSha256 == modelManager.verifiedSha256()) { "modelo LFM local não está sincronizado com o GGUF verificado" }
            return runBlocking {
                mutex.withLock {
                    val loaded = loadModelIfNeeded()
                    nativeRunner.run {
                        val completeStarted = System.nanoTime() / 1_000_000L
                        try {
                            val result = Llama.complete(loaded, prompt = prompt, systemPrompt = systemPrompt, maxTokens = maxTokens)
                            BrainCompletion(result.text, LfmModelSpec.MODEL_ID, "local", "on-device")
                        } finally {
                            logLfmDiagnostic("Llama.complete", completeStarted)
                        }
                    }
                }
            }
        } finally {
            logLfmDiagnostic("runInference", started)
        }
    }

    fun close() = runCatching {
        model?.let(Llama::releaseModel)
        model = null
        loadedSha256 = null
        nativeRunner.close()
    }
}

/**
 * Camada 2 (llm) para Porta 1/2: rascunho gerado pelo LFM local (gratuito, on-device).
 * Nunca chama rede/api — modelo indisponível ou erro devolve string vazia, e quem chamou
 * (ChatResponseExecutor/PromptGenerationExecutor) segue normalmente para a camada 3 (web).
 */
class LfmConversationDrafter(
    private val local: LocalLlmBrainApiGateway
) : com.brain.conversation.ConversationDrafter {
    override fun rascunhar(prompt: String, context: com.brain.conversation.ConversationContext): String =
        runCatching { local.completeDraft(prompt).text.trim() }.getOrDefault("")
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

/** Local-only advisor: deterministic fallback, with no cloud/token path. */
class LocalOnlyIntentAdvisor(private val gateway: BrainApiGateway) : IntentAdvisor {
    override fun revisarClassificacao(prompt: String, classificacaoTentativa: OrderIntent): OrderIntentSugerido =
        runCatching { LfmIntentAdvisor(gateway).revisarClassificacao(prompt, classificacaoTentativa) }
            .getOrElse { OrderIntentSugerido(door = classificacaoTentativa.door, confidence = 0.0, rationale = "local-fallback") }
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
            "cidade", "estado", "país", "nome", "empresa", "data",
            "endereço", "endereco", "bairro", "rua", "avenida", "cep",
            "telefone", "email"
        )
    }
}
