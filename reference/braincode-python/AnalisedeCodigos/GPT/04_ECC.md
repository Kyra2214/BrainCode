# 04 — affaan-m/ECC

## Pente fino adicional

O valor do ECC fica mais claro quando se olha para ele como uma **camada de engenharia ao redor dos agentes**. O padrão plan → test → implement → review → verify → remember → improve deve ser convertido no Braim em estados persistentes, hooks e evidências.

### Hooks como middleware de segurança

Hooks não devem conter lógica de negócio de cada agente. Eles devem aplicar políticas transversais: validar tool call, registrar execução, detectar segredo, rodar teste, bloquear entrega e registrar aprendizado.

```text
Agent
 ↓
before_agent_run
 ↓
before_tool_call
 ↓
Tool
 ↓
after_tool_call
 ↓
after_agent_run
 ↓
Evaluation
```

### Rules versus Skills

Rule = política/limite que deve prevalecer.
Skill = procedimento para executar algo.
Memory = evidência do que ocorreu.

Essa separação evita que uma experiência ruim vire automaticamente uma regra ou Skill.

### Learning loop

O aprendizado deve registrar recompensa/penalidade baseada em resultado real, e não apenas na opinião do LLM. O mínimo: sucesso, teste, qualidade, custo, tempo, erro e correção.

### Segurança de agentes

O conceito de AgentShield é especialmente útil para o futuro minerador de Skills/agents: conteúdo externo deve ser analisado antes de entrar no ambiente de execução.

## Objetivo

Estudar um framework grande de agentes, Skills, hooks, regras, memória, aprendizado contínuo e segurança para absorver padrões de operação no Braim.

## O que o ECC apresenta

O ECC se posiciona como um sistema de engenharia para agentes: planejar → testar → implementar → revisar → verificar → lembrar → melhorar. O README atual declara agentes especializados, centenas de Skills, comandos, hooks, regras, memória, continuous learning e AgentShield.

## O que mais interessa

### 1. Pipeline explícito

O fluxo `plan -> test -> implement -> review -> verify -> remember -> improve` é muito próximo do que queremos no Braim. A diferença é que o Braim deve transformar isso em um grafo de estados persistente, não apenas uma convenção de prompt.

### 2. Agentes especializados

O ECC possui agentes para planejamento, revisão, correção de build, segurança, arquitetura e domínios específicos. Absorver a ideia de especialização é correto; importar dezenas de agentes logo de início não é.

O Braim deve começar com poucos agentes de alta utilidade e aprender quando criar/ativar especialistas adicionais.

### 3. Skills

O ECC reforça a ideia de Skills como unidade reutilizável. O Braim pode combinar o formato de `SKILL.md` com métricas próprias.

### 4. Hooks

Hooks são interessantes para políticas automáticas antes/depois de ações. No Braim:

- `before_tool_call`;
- `after_tool_call`;
- `before_agent_run`;
- `after_agent_run`;
- `before_delivery`;
- `on_failure`;
- `on_secret_access`.

### 5. Rules

Regras carregadas seletivamente por projeto/linguagem são uma boa separação entre conhecimento procedural e políticas permanentes.

### 6. Memória e continuous learning

Essa parte é muito relevante. O ECC tenta transformar resultados anteriores em memória reutilizável. O Braim deve fazer algo parecido, porém com dados estruturados:

```text
problema
estratégia
agente
skill
modelo
API
resultado
qualidade
custo
latência
erro
correção
```

### 7. AgentShield

A existência de um scanner dedicado para prompts, hooks, MCP, permissões, secrets e arquivos de agentes mostra que o próprio ambiente de agentes precisa ser auditado.

O Braim deve ter futuramente um `AgentSecurityScanner`.

## O que absorver

- ciclo plan/test/implement/review/verify/remember/improve;
- agentes especializados;
- Skills reutilizáveis;
- hooks de ciclo de vida;
- regras por contexto;
- memória de sessão;
- aprendizado contínuo;
- scanner de segurança para agentes;
- revisão em contexto separado;
- capacidade de sincronizar conceitos entre harnesses.

## O que não absorver

- grande quantidade de agentes imediatamente;
- 94 comandos legados como arquitetura do Braim;
- instalador sem auditoria;
- ECC Tools/serviços pagos;
- integração específica com Claude/Codex como dependência central.

## Segurança

O próprio ECC recomenda instalar somente fontes oficiais. Isso é uma lição importante para o “minerador de APIs/Skills” do Braim: **descobrir não significa instalar**. Tudo encontrado precisa passar por validação, licença, integridade e risco.

## Licença

O repositório é MIT. Ainda assim, cada recurso e integração pode possuir condições próprias. O Braim deve manter atribuição e origem quando absorver código.

## Prioridade

**ALTA.** Excelente referência para camada de políticas, memória, revisão e segurança.

## Fontes

- https://github.com/affaan-m/ECC
- https://github.com/affaan-m/ECC/blob/main/README.md

## Conclusão

O maior valor do ECC para o Braim é mostrar que **um agente produtivo é um sistema de engenharia ao redor do LLM**. O pente fino acrescenta a separação Rule/Skill/Memory e o uso de hooks como middleware transversal de segurança, observabilidade e aprendizado.
