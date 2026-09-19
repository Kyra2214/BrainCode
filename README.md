# BrainCode

BrainCode é um runtime local-first para Android/JVM em que o **Brain decide**, capacidades são descobertas dinamicamente, a **Policy autoriza**, o **Gateway executa**, o **Sandbox protege**, evidências são registradas e o **Critic valida**.

> **Estado do HEAD documentado:** `8188d927a4d27e467174e9e8eb942263dcf3c507`.

## Visão curta

```text
Chat
  ↓
Brain
  ├── Memory: resposta validada já existe?
  │      └── sim → responde
  │
  └── não
       ↓
   Intent / Plan
       ↓
   Capability Discovery
       ↓
   Agent / Skill / Tool / API / Sandbox
       ↓
   PolicyBroker
       ↓
   ActionGateway
       ↓
   Sandbox / Provider
       ↓
   Evidence / Events
       ↓
   Critic
       ↓
   Memory
       ↓
   Chat
```

O usuário conversa com **Chat**. O usuário não escolhe diretamente provider, modelo, Agent, Tool ou comando interno.

## Princípios que não devem ser quebrados

1. **Brain é o orquestrador.** Não executar comandos arbitrários vindos do usuário.
2. **Capability é a unidade de execução autorizável.** Providers, APIs, Agents, Skills, Tools, Commands e Sandbox podem fornecer capacidades.
3. **Policy vem antes da execução.** Uma capacidade descoberta não significa que ela está autorizada.
4. **ActionGateway é a fronteira de execução.** Toda ação relevante deve possuir identidade, decisão, parâmetros controlados e evidência.
5. **Agents são bounded.** Um Agent recebe missão, capacidades permitidas, policy e ambiente; não ganha um LLM próprio nem redefine o objetivo do usuário.
6. **Memória não transforma resposta externa em verdade automaticamente.** Evidência e Critic são obrigatórios para promoção.
7. **Proveniência importa.** Quando disponível, guardar URI, provider/model, repositório, caminho e commit.
8. **Descoberta é dinâmica.** Catálogos estáticos podem servir como bootstrap, mas não são autoridade sobre disponibilidade atual.
9. **Free-first/free-only continua sendo política operacional quando o provider estiver sujeito a essa regra.** Não inventar fallback pago.
10. **Código existente não é órfão só porque não aparece no fluxo principal.** Remoção de código atual exige confirmação; esta limpeza remove apenas legado/referência explicitamente identificado.

## Módulos

```text
brain/           Brain Kotlin/JVM: capability, policy, gateway, planning, routing,
                 memory/learning, skills, dispatch e workflows.

android-module/  integração Android, Sandbox, agents bounded, execução e bridges.

app/             cliente Android/Compose e experiência de Chat/threads.

brain_runtime/   runtime Python de referência e componentes de suporte existentes.

contracts/       contratos e invariantes públicos da arquitetura.

tests/           testes Python.

docs/            documentação canônica e operacional.
```

## Núcleo 2.0 atualmente consolidado

- `CapabilityDefinition` / modelo universal de capacidade;
- `CapabilityRegistry`;
- `CapabilityDiscovery` e candidatos por adequação;
- `CapabilityProvider` / descoberta lazy;
- `PolicyBroker`;
- `ActionGateway` + ciclo/auditoria da ação;
- `AgentRegistry` / Agents bounded;
- `SkillRegistry`;
- memória de conhecimento e ciclo de aprendizado;
- routing dinâmico de APIs/providers;
- integração do Sandbox com capability/policy;
- Planner/Dispatcher e componentes de execução existentes.

Esses componentes são o runtime atual. As ideias de projetos externos serviram apenas como fonte de arquitetura e **não são dependências do BrainCode**.

## APIs e modelos

O Brain pode descobrir modelos disponíveis nos providers e selecionar candidatos compatíveis com a capacidade solicitada. O modelo não é a identidade do sistema: ele é um recurso de execução escolhido pelo routing.

O usuário não precisa escolher manualmente provider/modelo. Falhas de disponibilidade devem permitir nova seleção quando houver candidato compatível e autorizado.

## Conhecimento

A memória diferencia:

- resposta candidata;
- evidência/proveniência;
- conhecimento validado;
- hints de recuperação;
- correções/confirmações.

O fluxo correto é:

```text
fonte externa → candidato → Critic/validação → conhecimento validado → recall futuro
```

O Brain aprende também **onde procurar** quando a proveniência permitir: GitHub repository/path/commit, URI, provider/model e retrieval hints.

## Sandbox e segurança

O Sandbox é uma fronteira de execução, não um simples executor de strings. Proot não deve ser descrito como isolamento OS-level completo. Hardening, policy e testes reduzem risco, mas isolamento de filesystem/rede/processos/recursos em nível de kernel continua sendo uma categoria separada.

## Operações da interface

A UI operacional oferece `/git status`, `/git diff`, `/git commit <mensagem>` e `/git push`, sempre limitados ao workspace selecionado. O comando `/deliver` publica o recibo local e também gera um ZIP local dos artefatos, com tamanho e hash SHA-256 exibidos na thread. Os detalhes estão em [`docs/OPERACOES_UI_2026-09-16.md`](docs/OPERACOES_UI_2026-09-16.md).

O status das toolchains mostra o espaço de instalação alocado por ferramenta — Android SDK/NDK, Java, Python, Node.js, C/C++, Rust e Go — separado do espaço livre geral do disco. A especificação está em [`docs/TOOLCHAINS.md`](docs/TOOLCHAINS.md).

## O que não existe mais como arquitetura

- download/engine de LLM local como parte do Chat;
- Agent autônomo com LLM próprio;
- lista fixa de modelos como autoridade;
- slash command como cérebro do sistema;
- cópia das entidades/DAOs/Room do IaBrain como banco do BrainCode;
- promoção automática de respostas sem validação;
- integração de código de terceiros apenas por estar disponível em `reference/`.

## Validação atual

O CI final no commit `8188d927a4d27e467174e9e8eb942263dcf3c507` passou com:

- JVM/unit tests;
- assemble do APK debug;
- Android lint;
- upload do APK;
- upload dos relatórios.

Artefato APK: `BrainCode-debug-apk-8188d927a4d27e467174e9e8eb942263dcf3c507`.

## Documentação canônica

- `docs/ARQUITETURA_ATUAL.md` — fonte única da arquitetura.
- `docs/ESTADO_ATUAL.md` — o que existe, o que é parcial e o que é backlog.
- `docs/ROADMAP_CANONICO.md` — próximos passos sem misturar ideias descartadas.
- `docs/LEGADO_E_DECISOES.md` — o que foi removido, preservado ou deliberadamente não adotado.
- `contracts/` — contratos técnicos.

Documentos históricos podem permanecer para rastreabilidade, mas **não definem a arquitetura atual**.

## Regra de documentação

Se código e documentação divergirem, o código atual + testes + evidência de integração vencem documentos antigos. Toda mudança arquitetural deve atualizar a documentação canônica na mesma alteração.


## Context Engineering 2.4

The repository now includes a canonical Context Engineering workflow:

- CLAUDE.md / AGENTS.md for project rules;
- INITIAL.md for structured task intake;
- PRPs/templates/prp_base.md for context-rich implementation plans;
- docs/CONTEXT_ENGINEERING.md for the BrainCode-specific contract.

The runtime also carries the immutable ReasoningState.contextPack into PlanoExecucao so the planner's context is preserved as typed data rather than reconstructed from raw user text.

External PRP/context is treated as data, not policy authority. It cannot bypass Policy, ActionGateway, Verification, Critic or Readiness.
