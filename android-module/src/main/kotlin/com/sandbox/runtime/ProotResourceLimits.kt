package com.sandbox.runtime

/**
 * Limites reais de recurso (nível kernel) para comandos executados dentro do
 * `proot`, via `setrlimit(2)`.
 *
 * ## Por que não cgroups aqui
 *
 * O `CgroupController` em `brain_runtime/host_controls.py` (caminho de
 * referência Python) depende de um controller cgroup v2 delegado com
 * permissão de escrita — algo que normalmente não existe em Android sem
 * root: o app não tem `CAP_SYS_ADMIN` nem um `cgroup.subtree_control`
 * delegado pelo `init`/`system_server` para sua própria hierarquia. Tentar
 * usar cgroups no device seria uma promessa que a maioria dos aparelhos não
 * consegue cumprir.
 *
 * ## Por que `ulimit` funciona mesmo sob `proot`
 *
 * `proot` é baseado em `ptrace` — ele nunca cria namespace de PID, mount ou
 * usuário, e também nunca intercepta ou reescreve `setrlimit`/`getrlimit`
 * (não são syscalls baseadas em path, então não fazem parte do que o
 * `proot` traduz). Quando o `bash` chamado por `ProotProcessLauncher`/
 * `SandboxRuntime` executa o builtin `ulimit`, ele está chamando
 * `setrlimit(2)` de verdade, sobre o processo host real por trás do
 * `ptrace` — não uma simulação. `setrlimit` sobrevive a `fork()` e a
 * `execve()` por definição (POSIX), então o limite aplicado antes do
 * `exec` do comando do agente vale igualmente para o binário real
 * (`python3`, `node`, etc.) que o substitui.
 *
 * ## Por que `maxProcesses` (RLIMIT_NPROC) fica desligado por padrão
 *
 * Ao contrário de `RLIMIT_AS`, `RLIMIT_CPU`, `RLIMIT_NOFILE` e
 * `RLIMIT_FSIZE` — que são por processo — o Linux define `RLIMIT_NPROC`
 * como um teto agregado por **UID real**, contado no sistema inteiro (todas
 * as threads de todos os processos daquele UID, não só da árvore do
 * sandbox). No Android, `proot` não muda o UID real (o `-0` só falseia o
 * UID visto de dentro via `ptrace`, não faz `setuid` de verdade) — então o
 * processo do agente sandboxed compartilha o mesmo UID real do próprio app
 * host. Um teto baixo (ex.: 32) não isola nada: ele passa a competir com as
 * threads do próprio processo Android (GC, Binder, etc.), podendo fazer
 * `fork()`/`clone()` de qualquer parte do app falhar de forma
 * imprevisível e sem relação com o que o sandbox está fazendo. Por isso
 * `maxProcesses` aqui é opt-in e documentado, nunca ligado por padrão —
 * diferente do `brain_runtime` de referência, onde o processo Python roda
 * isolado em seu próprio UID/host e essa contagem por UID de fato limita só
 * a própria árvore.
 *
 * Isso não é um "quase cgroup": é um limite real, mas com uma superfície
 * mais estreita que cgroups (sem PIDs namespace de verdade, sem I/O
 * throttling). O ponto é não prometer o que o Android sem root não entrega,
 * e não aplicar um limite cujo efeito colateral é pior que a ausência dele.
 */
data class ProotResourceLimits(
    val maxMemoryBytes: Long? = null,
    val maxCpuSeconds: Int? = null,
    val maxProcesses: Int? = null,
    val maxOpenFiles: Int? = null,
    val maxFileSizeBytes: Long? = null
) {
    init {
        maxMemoryBytes?.let { require(it > 0) { "maxMemoryBytes deve ser positivo" } }
        maxCpuSeconds?.let { require(it > 0) { "maxCpuSeconds deve ser positivo" } }
        maxProcesses?.let { require(it > 0) { "maxProcesses deve ser positivo" } }
        maxOpenFiles?.let { require(it > 0) { "maxOpenFiles deve ser positivo" } }
        maxFileSizeBytes?.let { require(it > 0) { "maxFileSizeBytes deve ser positivo" } }
    }

    /**
     * Estatuses `ulimit` independentes, um por linha (`;`, nunca `&&`): uma
     * flag não suportada por um `/bin/bash` minimalista não deve
     * silenciosamente pular as demais. `-v` e `-f` usam blocos de 1024
     * bytes — a unidade do `bash` (confirmado empiricamente: `ulimit -f 1`
     * trunca em exatamente 1024 bytes), não os blocos de 512 bytes do POSIX
     * genérico. Por isso o guest sempre roda via `/bin/bash`, nunca
     * `/bin/sh`/`dash`.
     */
    fun ulimitStatements(): List<String> = buildList {
        maxMemoryBytes?.let { add("ulimit -v ${it / 1024}") }
        maxCpuSeconds?.let { add("ulimit -t $it") }
        maxProcesses?.let { add("ulimit -u $it") }
        maxOpenFiles?.let { add("ulimit -n $it") }
        maxFileSizeBytes?.let { add("ulimit -f ${it / 1024}") }
    }

    fun hasLimits(): Boolean = ulimitStatements().isNotEmpty()

    /**
     * Prefixo de comando `bash` que aplica os limites e depois imprime, em
     * stderr, os valores efetivos que o kernel confirmou — prefixados com
     * [MARKER] para o chamador conseguir distinguir "o limite foi aplicado
     * e o programa convidado simplesmente usou menos que o teto" de "o
     * pedido de limite foi silenciosamente ignorado". Um código de saída
     * sozinho nunca permite essa distinção.
     */
    fun verifiedPreamble(): String {
        if (!hasLimits()) return ""
        val applied = ulimitStatements().joinToString(separator = "; ", postfix = "; ")
        return applied + "echo \"$MARKER v=\$(ulimit -v) t=\$(ulimit -t) u=\$(ulimit -u) n=\$(ulimit -n) f=\$(ulimit -f)\" 1>&2; "
    }

    companion object {
        const val MARKER = "__resource_limit_verification__"

        /**
         * Baseado nos padrões do `SandboxJob` no caminho de referência Python
         * (`brain_runtime/sandbox.py`: `memory_mb=512`, `max_processes=32`,
         * `max_open_files=64`, `max_disk_bytes=100MB`, `RLIMIT_CPU=60`) —
         * O limite de processos usa RLIMIT_NPROC como camada adicional, mas o
         * watchdog Android em ManagedSandboxRuntime é a autoridade por árvore;
         * isso evita confiar apenas no UID compartilhado do Android.
         *
         * `maxMemoryBytes` foi elevado de 512MB para 4GB porque `ulimit -v`
         * mapeia para `RLIMIT_AS`, que limita **espaço de endereçamento
         * virtual reservado**, não RSS/memória física de fato usada. JVM e
         * Go reservam de forma legítima centenas de MB de endereço virtual
         * no boot (arenas de heap, tabelas de sumário do page allocator,
         * guard pages de stack) mesmo processando cargas pequenas — a 512MB
         * isso já falha antes do primeiro `java --version` ou `go version`
         * ("Could not reserve enough space for ...", "failed to reserve page
         * summary memory"), mesmo já reduzindo `-Xmx`. 4GB dá margem para
         * esses runtimes sem deixar de aplicar teto nenhum (`ulimit -v
         * unlimited` seguiria ausente); RSS real de processos individuais
         * ainda fica naturalmente contido pela memória física do aparelho.
         */
        val DEFAULT = ProotResourceLimits(
            maxMemoryBytes = 4L * 1024 * 1024 * 1024,
            maxCpuSeconds = 60,
            maxProcesses = 128,
            maxOpenFiles = 64,
            maxFileSizeBytes = 100L * 1024 * 1024
        )

        /** Nenhum limite — usado por quem precisa reproduzir o comportamento antigo (sem RLIMIT algum). */
        val NONE = ProotResourceLimits()
    }
}

/**
 * Lê a linha de verificação emitida por [ProotResourceLimits.verifiedPreamble]
 * dentro de um blob de stderr já capturado, separando-a do restante (para
 * não vazar para quem só quer ver o stderr real do comando do usuário).
 */
object ResourceLimitVerification {
    private val LINE_REGEX = Regex(
        "^${Regex.escape(ProotResourceLimits.MARKER)} v=(\\S+) t=(\\S+) u=(\\S+) n=(\\S+) f=(\\S+)$"
    )

    data class Parsed(val memoryBlocksKb: String, val cpuSeconds: String, val maxProcs: String, val openFiles: String, val fileBlocksKb: String)

    /** Remove a(s) linha(s) de marcação do stderr, devolvendo o texto limpo e a última linha reconhecida (se houver). */
    fun extract(stderr: String): Pair<String, Parsed?> {
        var parsed: Parsed? = null
        val kept = stderr.lineSequence().filterNot { line ->
            LINE_REGEX.find(line.trim())?.let { match ->
                val groups = match.groupValues
                parsed = Parsed(groups[1], groups[2], groups[3], groups[4], groups[5])
                true
            } ?: false
        }.joinToString("\n").trimEnd('\n')
        return kept to parsed
    }

    /**
     * `true` só quando cada limite configurado bate com o que o kernel
     * confirmou. `unlimited`/valores fora do esperado indicam que o
     * `ulimit` correspondente não pegou (ex.: `/bin/bash` minimalista sem
     * suporte à flag) — isso deve ser tratado como falha de isolamento, não
     * como sucesso silencioso.
     */
    fun matches(limits: ProotResourceLimits, parsed: Parsed?): Boolean {
        if (!limits.hasLimits()) return true
        if (parsed == null) return false
        limits.maxMemoryBytes?.let { if (parsed.memoryBlocksKb != (it / 1024).toString()) return false }
        limits.maxCpuSeconds?.let { if (parsed.cpuSeconds != it.toString()) return false }
        limits.maxProcesses?.let { if (parsed.maxProcs != it.toString()) return false }
        limits.maxOpenFiles?.let { if (parsed.openFiles != it.toString()) return false }
        limits.maxFileSizeBytes?.let { if (parsed.fileBlocksKb != (it / 1024).toString()) return false }
        return true
    }
}
