package com.sandbox.agent

import com.brain.policy.ExecutionAuthorization
import com.sandbox.runtime.ManagedSandboxRuntime
import java.io.File

/**
 * Fachada única para abrir sessões de trabalho de agente — Etapa 3 do plano de
 * integração Brain+Sandbox original (docs/PLANO_INTEGRACAO_BRAIN_SANDBOX.md,
 * consolidado em docs/ARQUITETURA_ATUAL.md e docs/LEGADO_E_DECISOES.md; não
 * existe mais como arquivo separado).
 *
 * `Sandbox.abrirSessao(autorizacao)` no doc original era pseudocódigo do fluxo, não
 * literalmente um `object` estático: um [Sandbox] real precisa saber ONDE
 * o rootfs já foi extraído e qual [ManagedSandboxRuntime] usar — ambos
 * vêm do [com.sandbox.android.AndroidSandboxFactory] num device real (ver
 * [com.sandbox.android.AndroidSandboxFactory.createSandbox]). Em teste
 * (JVM puro, sem Android), constrói-se um [Sandbox] com um launcher fake,
 * igual ao resto de `:android-module`.
 *
 * @property rootfsDir pasta host onde o rootfs já foi extraído (a mesma
 *   passada pro [ManagedSandboxRuntime] por baixo) — é dentro dela que o
 *   workspace de cada sessão é criado de fato.
 * @property workspaceGuestRoot caminho, visto de dentro do proot, sob o
 *   qual cada sessão ganha uma subpasta isolada por [ExecutionAuthorization.runId].
 * @property capabilityResolver catálogo capacidade -> comando (Etapa 4),
 *   compartilhado por todas as sessões abertas por este [Sandbox]. Um
 *   catálogo customizado só faz sentido em teste; em produção o default
 *   ([CapabilityResolver.defaultCatalog]) já reflete o que o rootfs embute.
 */
class Sandbox(
    private val runtime: ManagedSandboxRuntime,
    private val rootfsDir: File,
    private val workspaceGuestRoot: String = "/home/sandbox/workspace",
    private val capabilityResolver: CapabilityResolver = CapabilityResolver()
) {
    /**
     * Única forma de conseguir uma [AgentSandboxSession]. Não há overload
     * que aceite parâmetros soltos (comando, workspace, etc) no lugar de
     * uma [ExecutionAuthorization] — isso repetiria, no lado do Sandbox, o
     * mesmo furo que [ExecutionAuthorization] já fecha do lado da Policy.
     *
     * Cada `runId` ganha seu próprio diretório de workspace, criado sob
     * demanda — sessões com o mesmo `runId` reabrem o mesmo workspace
     * (útil pra retomar um job depois de uma falha), sessões com `runId`
     * diferente nunca enxergam o diretório umas das outras.
     */
    fun abrirSessao(autorizacao: ExecutionAuthorization): AgentSandboxSession {
        val guestPath = "$workspaceGuestRoot/${autorizacao.runId}"
        val hostDir = File(rootfsDir, guestPath.removePrefix("/"))
        return AgentSandboxSession(
            authorization = autorizacao,
            runtime = runtime,
            workspaceHostDir = hostDir,
            workspaceGuestPath = guestPath,
            capabilityResolver = capabilityResolver
        )
    }
}
