# Comparação GPT × Claude × Manus — aplicação por sessão do Braim

## Objetivo

Consolidar as 13 análises produzidas pelas três linhas de análise (GPT, Claude e Manus) e transformar convergências em decisões arquiteturais para o Braim.

Regra de decisão:
- 🟢 convergência forte: entra como requisito/arquitetura-base.
- 🟡 convergência parcial: entra com validação e limites explícitos.
- 🔵 contribuição de uma linha: tratar como hipótese até validação no código/origem.
- 🔴 conflito: não incorporar automaticamente; resolver pela evidência do código.

## Síntese das três linhas

### Manus
Melhor fonte para profundidade de arquitetura, fluxo de execução, evidências, riscos e detalhes de implementação. Deve funcionar como referência de descoberta e auditoria profunda.

### GPT
Melhor fonte para traduzir descobertas em contratos do Braim, interfaces, responsabilidades e decisões de implementação. Deve funcionar como camada de síntese arquitetural.

### Claude
Melhor contribuição para padrões operacionais, composição de capacidades, Skills, workflows, versionamento e práticas de engenharia. Deve funcionar como fonte complementar para desenho modular.

### Regra final
Não copiar nenhum projeto. Extrair padrões comprovados, separar núcleo de extensões e validar cada decisão contra os limites do Braim: "IaBrain pensa. Agentes trabalham. SandBox executa." 

---

# Mapa por sessão do Braim

## Sessão 1 — Entrada e entendimento da tarefa

**Utilizar:**
- 🟢 Skills estruturadas de Anthropic Skills + ECC.
- 🟢 classificação/capability discovery de OpenCode.
- 🟡 decomposição de tarefas inspirada em LangChain/ClawFlows.

**Implementar:**
1. Normalizar pedido do usuário.
2. Identificar objetivo, restrições, contexto, entregáveis e critérios de sucesso.
3. Classificar a tarefa por capacidades necessárias.
4. Gerar um TaskSpec imutável antes do planejamento.

**Não fazer:** deixar o LLM decidir sozinho o estado interno do Braim.

**Contrato sugerido:** `TaskSpec {task_id, session_id, objective, constraints, inputs, expected_outputs, success_criteria, capabilities, priority}`.

---

## Sessão 2 — Secretário/local LLM: conhecimento e descoberta

**Utilizar:**
- 🟢 LEANN para recuperação/indexação semântica.
- 🟢 catálogo de APIs e fontes inspirado em Best APIs for Lead Gen + Job Data APIs.
- 🟡 GEO-SEO para pesquisa controlada e evidência de fontes.

**Implementar:**
- perguntar ao secretário o que já é conhecido;
- consultar memória/indexador;
- detectar lacunas;
- decidir se precisa pesquisar;
- registrar fontes e proveniência.

**Importante:** o secretário não vira o cérebro. Ele fornece avaliação, contexto e evidência para o Planner.

**ResearchPolicy:** timeout, limite de páginas, concorrência, tamanho máximo de resposta, robots/termos, deduplicação, confiança e proveniência.

---

## Sessão 3 — Planner / decomposição em fases

**Utilizar:**
- 🟢 Codex para contratos de execução e relação Brain↔Sandbox.
- 🟢 OpenCode para agentes, ferramentas, permissões e rollback por etapa.
- 🟢 ClawFlows/n8n para composição de workflows e estados.
- 🟡 LangChain para composição de componentes.

**Implementar:**
`Task → Plan → Phase → Step → Capability → Agent/Tool → expected result`.

Cada Step deve possuir:
- `step_id`;
- dependências;
- agente/capacidade necessária;
- entradas;
- saída esperada;
- timeout;
- política de retry;
- condição de sucesso;
- política de fallback;
- permissões;
- parent_step_id.

O Planner não executa comandos. Ele produz plano executável para o Task Engine.

---

## Sessão 4 — Geração de prompts e seleção do especialista

**Utilizar:**
- 🟢 Skills como unidades de capacidade.
- 🟢 OpenCode/ECC para seleção de agente e contexto.
- 🟢 catálogo de APIs/provedores para capacidade, custo, quota, latência e confiabilidade.
- 🟡 aprendizado por histórico para melhorar roteamento.

**Implementar:**
`CapabilityRegistry → Provider/Agent Registry → RoutingPolicy → PromptBuilder → Dispatch`.

O prompt não deve ser o contrato principal. O contrato é estruturado; o prompt é apenas uma representação para o modelo especialista.

Registrar por execução:
- provedor/modelo;
- capability;
- prompt/version;
- custo;
- latência;
- resultado;
- qualidade;
- erros;
- retries;
- fallback utilizado.

---

## Sessão 5 — Execução pelos agentes/especialistas

**Utilizar:**
- 🟢 OpenCode: agentes + ferramentas + permissões.
- 🟢 Codex: execução estruturada e sandbox.
- 🟢 n8n: contratos versionados de nodes/integrações.
- 🟡 Skills: capacidades instaláveis/versionadas.

**Regra:** agentes trabalham; não controlam o estado global do Braim.

Cada agente recebe um `ExecutionRequest` e devolve um `ExecutionResult` estruturado.

Agentes não devem possuir autoridade implícita para:
- alterar memória global;
- trocar provedor sem autorização;
- executar comandos fora do Sandbox;
- persistir credenciais;
- mudar o plano original sem registrar uma decisão.

---

## Sessão 6 — Execução no Sandbox

**Utilizar:**
- 🟢 Codex como principal referência.
- 🟢 OFFPack para cache/offline/prefetch e manifesto de dependências.
- 🟡 n8n para contratos de execução/versionamento.

**Arquitetura:**
`Braim → SandboxJob → execução → testes → artefatos → SandboxJobResult → Braim`.

Campos mínimos:
- job_id;
- idempotency_key;
- session_id;
- comandos/arquivos;
- toolchain;
- permissões;
- secret_refs;
- timeout/cancelamento;
- stdout/stderr;
- exit_code;
- testes;
- arquivos alterados;
- artefatos;
- diagnósticos.

**Regra absoluta:** Sandbox não chama IA. A IA/Braim chama o Sandbox.

OFFPack não entra no Core do Braim; entra como componente do ecossistema de execução/cache quando necessário.

---

## Sessão 7 — Validação, crítica, correção e retry

**Utilizar:**
- 🟢 Codex: execução/testes/eventos/cancelamento.
- 🟢 OpenCode: rollback por etapa.
- 🟢 GEO-SEO: quality gates e score com evidências.
- 🟡 ECC: hooks/gates de qualidade.
- 🟢 Business Logic/Race-condition principles: validar estado e concorrência.

**Implementar ciclo:**
`Result → Validate → Critic → Decision → Pass | Correct | Retry | Fallback | Abort`.

Nunca usar apenas `HTTP 500` ou uma exceção genérica como critério de falha.

Validar:
- completude;
- schema/contrato;
- testes;
- invariantes;
- qualidade;
- evidências;
- segurança;
- timeout;
- custo;
- consistência do estado.

Correção deve referenciar o `step_id` que falhou e preservar histórico do resultado anterior.

---

## Sessão 8 — Entrega, memória e aprendizado

**Utilizar:**
- 🟢 Job Data APIs para proveniência/sincronização resiliente.
- 🟢 LEANN para recuperação/indexação.
- 🟢 Playbooks para transformar experiências em procedimentos reutilizáveis.
- 🟢 catálogo de APIs para atualizar métricas de provedores.
- 🟡 ECC para memória/contexto operacional.

**LearningRecord:**
`Problem → Source → Strategy → API/Provider → Skill → Prompt → Agent → Result → Cost → Time → Quality → Errors → Reward/Penalty`.

A memória deve separar:
1. fatos;
2. decisões;
3. experiências de execução;
4. estratégias;
5. métricas;
6. evidências;
7. Skills/playbooks.

Não gravar simplesmente o texto bruto de cada conversa como "aprendizado".

---

# O que entra em cada camada

| Projeto/fonte | Core | Skill | Tool | Agent | Sandbox | Memory | Workflow |
|---|---|---|---|---|---|---|---|
| OpenAI Codex | 🟢 contratos/eventos | 🟡 | 🟢 | 🟡 | 🟢 referência | 🟡 | 🟢 |
| OpenCode | 🟡 arquitetura | 🟢 | 🟢 | 🟢 | 🟡 | 🟡 | 🟢 |
| Anthropic Skills | 🟡 registry | 🟢 principal | 🟡 | 🟢 | 🔴 | 🟡 | 🟢 |
| ECC | 🟡 gates | 🟢 | 🟡 | 🟡 | 🔴 | 🟢 | 🟢 |
| LangChain | 🟡 abstrações | 🟡 | 🟢 | 🟡 | 🔴 | 🟡 | 🟢 |
| ClawFlows | 🔴 | 🟡 | 🟡 | 🟢 | 🔴 | 🟡 | 🟢 principal |
| n8n | 🟡 contratos | 🟡 | 🟢 | 🟢 | 🟡 | 🟡 | 🟢 principal |
| LEANN | 🔴 núcleo do Brain | 🟡 | 🟢 retrieval | 🔴 | 🔴 | 🟢 retrieval | 🟡 |
| Lead Gen APIs | 🟢 catálogo/policy | 🟡 | 🟢 principal | 🟡 | 🔴 | 🟢 métricas | 🟢 |
| Job Data APIs | 🟡 registry | 🟡 | 🟢 | 🟡 | 🔴 | 🟢 proveniência | 🟢 sync |
| Software Income Playbooks | 🔴 | 🟢 principal | 🟡 | 🟢 | 🔴 | 🟢 experiências | 🟢 |
| GEO-SEO | 🟡 quality/evidence | 🟢 | 🟢 | 🟢 especialista | 🔴 | 🟡 | 🟢 |
| OFFPack | 🔴 | 🔴 | 🟢 | 🔴 | 🟢 principal | 🟡 | 🟡 |

---

# Decisões arquiteturais resultantes

## 1. Core mínimo

O Core do Braim deve conter apenas:
- Task/Session state;
- Planner;
- Task Engine;
- Capability/Skill registry;
- Provider/Agent registry;
- Routing policy;
- Memory interfaces;
- Policy/permissions;
- Validation/Critic;
- Event bus;
- Learning records;
- contratos Brain↔Agent↔Sandbox.

## 2. Não colocar no Core

Não incorporar diretamente:
- LangChain completo;
- n8n completo;
- OpenCode completo;
- ClawFlows completo;
- OFFPack como gerenciador central;
- qualquer modelo de IA específico;
- prompts específicos de terceiros;
- UI/TUI de projetos externos.

## 3. Primeiras capacidades a construir

Ordem recomendada:
1. `TaskSpec` + `SessionState`;
2. `CapabilityRegistry`;
3. `Agent/Provider Registry`;
4. `Plan/Step` + Task Engine;
5. `ExecutionRequest/Result`;
6. Sandbox contract;
7. Validator/Critic;
8. Memory + LearningRecord;
9. ResearchPolicy + SourceRegistry;
10. API/Provider scoring;
11. Skill lifecycle;
12. workflow engine avançado.

## 4. Segurança e concorrência

Devem ser requisitos do Core desde o início, não uma fase posterior:
- autorização explícita por capability;
- secret references em vez de secrets no contexto;
- idempotência;
- timeout/cancelamento;
- locks para estado compartilhado;
- prevenção de TOCTOU;
- transições de estado explícitas;
- auditoria de decisões;
- rollback/compensação;
- limites de recursos;
- isolamento de execução.

## 5. Critério de aprendizagem

O Braim não deve aprender apenas "qual resposta foi boa". Deve aprender qual combinação de:
`problema + estratégia + capability + agente + provedor + prompt + ferramenta + contexto + custo + tempo + validação`
produziu determinado resultado.

Isso permite posteriormente aprender roteamento e estratégia, em vez de apenas acumular memória textual.

---

# Resultado final

A comparação das três linhas aponta para uma arquitetura híbrida:

`User → Session → TaskSpec → Research/Memory → Planner → Router → Agent/Skill/Tool → Sandbox → Validator/Critic → Retry/Fallback → Deliver → Memory/Learning`

**Manus** fornece profundidade/evidência, **GPT** transforma isso em contratos e decisões do Braim, e **Claude** reforça modularidade operacional, Skills, workflows e práticas de engenharia.

A conclusão mais forte é que o Braim não deve ser uma cópia de nenhum dos 13 projetos. Ele deve ser o sistema que coordena esses padrões: pensa, decide, roteia, controla estado, valida, aprende e somente então delega trabalho ou execução.
