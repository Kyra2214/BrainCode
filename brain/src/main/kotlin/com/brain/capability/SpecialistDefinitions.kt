package com.brain.capability

/**
 * Definição dos 12 especialistas bounded para Porta 3.
 * Cada um é uma capability registrada no CapabilityRegistry
 * com provenance local (sem provider ou LLM próprio).
 *
 * IDs usam o mesmo namespace "agent.*" já usado por BuiltInAgentDefinitions,
 * CreationWorkflowPlanner (responsibleAgentId / SpecialistAssignment.specialistId)
 * e ValidationContractRegistry — não um namespace "specialist.*" à parte. Esta classe
 * é a fonte de conteúdo de prompt (responsabilidades/entrada/saida) para esses mesmos
 * agentes; BuiltInAgentDefinitions.boundedSpecialists() continua sendo a fonte dos campos
 * de policy (risk, providedCapabilities) e não deve ser registrada em paralelo com esta
 * para os mesmos IDs.
 */

data class SpecialistDefinition(
    val capabilityId: String,
    val nome: String,
    val descricao: String,
    val responsabilidades: List<String>,
    val faseAplicavel: String,
    val entrada: String, // o que precisa para começar
    val saida: String    // o que entrega
)

object SpecialistDefinitions {

    fun getAllSpecialists(): List<SpecialistDefinition> = listOf(
        requirements(),
        architecture(),
        roadmap(),
        uiDesign(),
        code(),
        backend(),
        database(),
        security(),
        testEngineer(),
        codeReview(),
        integration(),
        release()
    )

    private fun requirements(): SpecialistDefinition = SpecialistDefinition(
        capabilityId = "agent.requirements",
        nome = "Especialista em Requisitos",
        descricao = "Consolida requisitos funcionais e não-funcionais, identifica pendências e prioriza escopo.",
        responsabilidades = listOf(
            "Coletar requisitos explícitos do usuário",
            "Identificar casos de uso críticos",
            "Documentar constraints e pressupostos",
            "Priorizar por impacto e viabilidade"
        ),
        faseAplicavel = "DISCUSSION",
        entrada = "Descrição do projeto, questões abertas",
        saida = "Documento de requisitos, lista de pendências, critérios de aceite"
    )

    private fun architecture(): SpecialistDefinition = SpecialistDefinition(
        capabilityId = "agent.architecture",
        nome = "Especialista em Arquitetura",
        descricao = "Define estrutura técnica, camadas, padrões e decisões arquiteturais.",
        responsabilidades = listOf(
            "Propor arquitetura de alto nível",
            "Definir padrões de design e implementação",
            "Documentar decisões técnicas (ADRs)",
            "Validar viabilidade técnica"
        ),
        faseAplicavel = "ARCHITECTURE",
        entrada = "Requisitos consolidados",
        saida = "Diagrama de arquitetura, ADRs, matriz de componentes"
    )

    private fun roadmap(): SpecialistDefinition = SpecialistDefinition(
        capabilityId = "agent.roadmap",
        nome = "Especialista em Roadmap",
        descricao = "Planeja entregas incrementais, marcos e dependências entre módulos.",
        responsabilidades = listOf(
            "Quebrar escopo em entregas viáveis",
            "Identificar dependências e ordenação",
            "Estimar esforço e cronograma",
            "Definir MVPs e fases"
        ),
        faseAplicavel = "ARCHITECTURE",
        entrada = "Arquitetura aprovada",
        saida = "Roadmap com fases, marcos e tarefas estimadas"
    )

    private fun uiDesign(): SpecialistDefinition = SpecialistDefinition(
        capabilityId = "agent.ui",
        nome = "Especialista em Design UI/UX",
        descricao = "Define interface do usuário, fluxos, acessibilidade e padrões visuais.",
        responsabilidades = listOf(
            "Criar wireframes e mockups",
            "Definir fluxos de usuário",
            "Documentar padrões e componentes",
            "Validar acessibilidade (WCAG)"
        ),
        faseAplicavel = "ARCHITECTURE",
        entrada = "Requisitos de interação",
        saida = "Protótipos, design system, documentação de fluxos"
    )

    /** Especialista genérico de implementação — id "agent.code" para corresponder ao
     *  responsibleAgentId atribuído por CreationWorkflowPlanner à tarefa "implementation"
     *  e ao BuiltInAgentDefinitions.codeAgent() já existente no registry. */
    private fun code(): SpecialistDefinition = SpecialistDefinition(
        capabilityId = "agent.code",
        nome = "Especialista em Implementação",
        descricao = "Implementa o escopo aprovado do projeto, preservando a arquitetura e os padrões existentes.",
        responsabilidades = listOf(
            "Implementar o escopo aprovado da tarefa",
            "Seguir a arquitetura e os padrões já definidos",
            "Escrever testes que cubram o comportamento implementado",
            "Não expandir escopo além do que foi aprovado"
        ),
        faseAplicavel = "EXECUTION",
        entrada = "Arquitetura aprovada, escopo da tarefa",
        saida = "Código implementado e testes associados"
    )

    private fun backend(): SpecialistDefinition = SpecialistDefinition(
        capabilityId = "agent.backend",
        nome = "Especialista em Backend",
        descricao = "Implementa lógica de negócios, APIs e integrações de serviços.",
        responsabilidades = listOf(
            "Implementar endpoints e lógica",
            "Validar contratos de dados",
            "Gerenciar estado e sessão",
            "Implementar autenticação e autorização"
        ),
        faseAplicavel = "EXECUTION",
        entrada = "Arquitetura aprovada, contrato de APIs",
        saida = "Serviço funcional, testes unitários, documentação de API"
    )

    private fun database(): SpecialistDefinition = SpecialistDefinition(
        capabilityId = "agent.database",
        nome = "Especialista em Database",
        descricao = "Projeta esquema, índices, migrations e otimizações de dados.",
        responsabilidades = listOf(
            "Projetar modelo de dados",
            "Definir índices e constraints",
            "Criar migrations versionadas",
            "Otimizar queries e desempenho"
        ),
        faseAplicavel = "EXECUTION",
        entrada = "Modelo conceitual de dados",
        saida = "Schema documentado, migrations, plano de backup"
    )

    private fun security(): SpecialistDefinition = SpecialistDefinition(
        capabilityId = "agent.security",
        nome = "Especialista em Segurança",
        descricao = "Valida segurança, criptografia, compliance e mitigação de riscos.",
        responsabilidades = listOf(
            "Identificar vetores de ataque",
            "Validar criptografia e TLS",
            "Verificar compliance (LGPD, OWASP)",
            "Revisar logs e auditoria"
        ),
        faseAplicavel = "INTEGRATION",
        entrada = "Código implementado, arquitetura",
        saida = "Relatório de segurança, lista de remediações"
    )

    private fun testEngineer(): SpecialistDefinition = SpecialistDefinition(
        capabilityId = "agent.test",
        nome = "Engenheiro de Testes",
        descricao = "Planeja estratégia de testes, cria casos e valida qualidade.",
        responsabilidades = listOf(
            "Definir estratégia de testes",
            "Escrever testes unitários e integração",
            "Executar testes E2E",
            "Reportar defeitos e regressões"
        ),
        faseAplicavel = "TESTS",
        entrada = "Código e requisitos",
        saida = "Plano de testes, relatório de cobertura, lista de bugs"
    )

    private fun codeReview(): SpecialistDefinition = SpecialistDefinition(
        capabilityId = "agent.review",
        nome = "Revisor de Código",
        descricao = "Revisa código quanto a qualidade, padrões e boas práticas.",
        responsabilidades = listOf(
            "Validar padrões de código",
            "Revisar legibilidade e manutenibilidade",
            "Sugerir refatorações",
            "Validar conformidade com style guide"
        ),
        faseAplicavel = "TESTS",
        entrada = "Código-fonte",
        saida = "Comentários de review, métricas de qualidade"
    )

    private fun integration(): SpecialistDefinition = SpecialistDefinition(
        capabilityId = "agent.integration",
        nome = "Especialista em Integração",
        descricao = "Integra componentes, valida contratos e testa end-to-end.",
        responsabilidades = listOf(
            "Integrar frontend e backend",
            "Testar fluxos completos",
            "Validar contratos de dados",
            "Configurar ambiente de teste"
        ),
        faseAplicavel = "INTEGRATION",
        entrada = "Componentes desenvolvidos",
        saida = "Sistema integrado, relatório de validação"
    )

    private fun release(): SpecialistDefinition = SpecialistDefinition(
        capabilityId = "agent.release",
        nome = "Especialista em Release",
        descricao = "Prepara entrega, documenta, gera artefatos e coordena deployment.",
        responsabilidades = listOf(
            "Preparar release notes",
            "Gerar artefatos (APK, WAR, ZIP)",
            "Documentar configuração de deployment",
            "Validar readiness para produção"
        ),
        faseAplicavel = "DELIVERY",
        entrada = "Sistema testado e validado",
        saida = "Artefatos, release notes, guia de deployment"
    )
}
