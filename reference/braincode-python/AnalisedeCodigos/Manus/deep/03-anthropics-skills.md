# Auditoria de fluxo e absorção — `anthropics/skills`

**Repositório:** [anthropics/skills](https://github.com/anthropics/skills)  
**Snapshot auditado:** `34040c9c568585f6929bedeaad110ad08f079624`  
**Escopo:** fluxo de descoberta, ativação, carregamento, execução de recursos e empacotamento de Skills. Não executei testes, conforme solicitado; a análise é de fluxo e arquitetura.

## 1. O que o repositório realmente é

O repositório contém **19 Skills de exemplo**, uma especificação curta, um template e um marketplace de plugins. Não há um daemon ou motor de execução próprio neste checkout. O runtime é o harness que consome os arquivos. Essa distinção é essencial: o código do repositório define o **contrato de conteúdo**, enquanto Claude Code, Claude.ai ou a API fornecem o mecanismo de seleção e execução.

O README descreve três famílias principais: `skills/`, `spec/` e `template/`. A árvore também contém `THIRD_PARTY_NOTICES.md` e `.claude-plugin/marketplace.json`. O inventário direto encontrou 419 arquivos versionados, com grande concentração nas Skills de documentos e na documentação multi-SDK de `claude-api`.

## 2. Fluxo completo observado

### 2.1 Descoberta do pacote

O ponto de entrada de distribuição é `.claude-plugin/marketplace.json`. Ele declara o marketplace `anthropic-agent-skills`, proprietário, descrição e versão `1.0.0`. Em seguida, declara plugins com `name`, `description`, `source`, `strict` e uma lista explícita de diretórios de Skills.

A configuração separa:

| Plugin | Diretórios declarados | Função |
|---|---|---|
| `document-skills` | `skills/xlsx`, `docx`, `pptx`, `pdf` | Processamento de documentos |
| `example-skills` | arte, design, comunicação, MCP, criação, testes e web artifacts | Exemplos operacionais |
| `claude-api` | `skills/claude-api` | Referência de API/SDK |
| `academy-guide` | `skills/academy-guide` | Roteamento para material de aprendizagem |
| `discernment-nudge` | `skills/discernment-nudge` | Perguntas de verificação após respostas |

Esse arquivo é um **catálogo de pertencimento**. Ele não contém lógica de seleção por intenção, execução de scripts, autorização de ferramentas ou persistência. Para o Brain, marketplace deve ser tratado como manifesto de distribuição, não como política de segurança.

### 2.2 Instalação e ativação

O README mostra dois caminhos: adicionar o repositório como marketplace e instalar um plugin específico, ou instalar diretamente um plugin. O mecanismo externo copia/cacheia a origem e disponibiliza os diretórios para o harness. O repositório, portanto, não define sozinho o caminho final de instalação.

A portabilidade aparece na convenção documentada de diretórios por harness: `.skills/` como caminho portátil, além de diretórios específicos de Claude, VS Code/Copilot, Cursor, Amp, Goose e OpenCode. A mesma unidade conceitual é transportada, mas o adaptador de instalação muda.

**Implicação para o Brain:** o instalador deve separar três estados:

1. **disponível:** pacote conhecido pelo catálogo;
2. **instalado:** conteúdo presente e hash verificado;
3. **habilitado:** roteador autorizado a considerar a Skill.

A instalação não deve habilitar automaticamente scripts ou conectores.

### 2.3 Descoberta por metadados

Cada Skill possui um `SKILL.md` com frontmatter. O template oficial é:

```yaml
---
name: template-skill
description: Replace with description of the skill and when Claude should use it.
---
```

O README explica que `name` é identificador único e `description` informa o que a Skill faz e quando deve ser usada. A descrição é, portanto, o mecanismo de **trigger**. O corpo não é carregado para todas as tarefas.

A Skill `academy-guide` mostra um caso real de roteamento semântico: a descrição lista frases e contextos que devem ativar a Skill, mas o corpo impõe uma regra de forte correspondência de intenção. A regra diz para responder primeiro, recomendar no máximo um ou dois itens e permanecer em silêncio quando a relação for apenas tangencial. Isso mostra que uma boa Skill contém tanto gatilhos quanto limites de não ativação.

### 2.4 Carregamento progressivo

A documentação da `skill-creator` explicita três níveis:

1. **metadados:** `name` e `description`, sempre disponíveis;
2. **corpo:** `SKILL.md`, carregado quando a Skill ativa;
3. **recursos:** scripts, referências e assets, carregados somente quando necessários.

O limite sugerido para o corpo é de 500 linhas. Referências grandes devem ter índice. A organização por variante (`references/aws.md`, `gcp.md`, `azure.md`) evita carregar informação de domínios irrelevantes.

Esse é o principal fluxo absorvível no Brain: o catálogo de prompts não deve ocupar o contexto inteiro. O roteador deve retornar uma decisão com `skill_id`, confiança, justificativa, recursos necessários e permissões solicitadas antes de inserir instruções no contexto.

### 2.5 Execução de recursos

As Skills demonstram três tipos de conteúdo:

- **instrução:** regras e sequência no Markdown;
- **referência:** documentação consultada sob demanda;
- **programa:** scripts determinísticos chamados pelo agente.

A `docx` é a evidência mais forte. `skills/docx/SKILL.md` orienta criar documentos com `docx` npm, editar documentos existentes desmontando o ZIP e modificando `word/document.xml`, ler com `pandoc` e validar com LibreOffice/Poppler. O diretório contém `scripts/accept_changes.py`, `comment.py`, `merge_runs.py`, helpers OOXML e muitos XSDs.

O fluxo de edição é explícito:

1. identificar se o pedido é criação, edição ou leitura;
2. escolher docx-js, unzip/XML ou pandoc;
3. para edição, remover links simbólicos do arquivo externo;
4. unir runs fragmentados com `merge_runs.py`;
5. editar XML sem pretty-print;
6. reempacotar com `zip -Xr`;
7. validar com `validate.py`;
8. para tracked changes, verificar autor, `w:ins` e `w:del`;
9. converter/renderizar para verificação visual.

Esse fluxo prova que uma Skill pode coordenar múltiplas ferramentas, formatos e validações. No Brain, cada passo deve virar uma operação observável, com pré-condições e permissões separadas.

A `mcp-builder` demonstra um segundo padrão. Ela organiza o trabalho em pesquisa/planejamento, implementação, revisão e avaliação. Exige nomes de ferramentas claros, schemas de entrada e saída, paginação, mensagens de erro acionáveis e anotações `readOnlyHint`, `destructiveHint`, `idempotentHint` e `openWorldHint`. Depois orienta criar perguntas de avaliação independentes, somente leitura, complexas e verificáveis.

A `webapp-testing` demonstra um terceiro padrão: primeiro verificar se é HTML estático; se for dinâmico, iniciar servidor com helper, esperar `networkidle`, inspecionar DOM, descobrir seletores e só então executar ações. O script `with_server.py` encapsula ciclo de vida de servidores. A regra “ler `--help` antes de ler o código do helper” reduz poluição de contexto e favorece uso black-box.

### 2.6 Criação e evolução de uma Skill

`skills/skill-creator/SKILL.md` define um ciclo iterativo:

1. capturar intenção, gatilhos, formato e critérios de sucesso;
2. entrevistar sobre edge cases, entradas, saídas e dependências;
3. escrever draft de `SKILL.md`;
4. criar dois ou três prompts realistas em `evals/evals.json`;
5. executar com Skill e baseline sem Skill;
6. escrever assertions enquanto as execuções ocorrem;
7. comparar resultados, tempo e tokens;
8. analisar por que uma versão venceu;
9. reescrever e repetir;
10. ampliar o conjunto de avaliações.

Os agentes `analyzer`, `comparator` e `grader` especializam a avaliação. O formato exige evidência, separa vencedor/perdedor e verifica se uma assertion realmente diferencia a Skill ou passa por coincidência. O modelo é importante para o Brain porque transforma uma Skill em componente versionável com melhoria guiada por evidência, sem depender apenas de opinião.

## 3. Fluxo de ativação no Brain

A adaptação recomendada é:

```text
Pedido do usuário
   ↓
Catálogo de Skills (metadados leves)
   ↓
Roteador de intenção + filtros de projeto
   ↓
Plano de ativação: Skill, recursos e permissões solicitadas
   ↓
Policy gate do Brain
   ↓
Carregamento do SKILL.md e referências necessárias
   ↓
Loop do agente
   ├── ferramenta nativa
   ├── script da Skill no Sandbox
   ├── MCP/conector aprovado
   └── pedido de aprovação humana
   ↓
Validação do resultado e artefatos
   ↓
Persistência de eventos, provenance e saída
```

O ponto que não existe no repositório Anthropic e precisa existir no Brain é o **policy gate**. A descrição pode sugerir quando usar uma Skill, mas não pode conceder acesso a shell, rede, segredo ou escrita.

## 4. O que absorver

| Prioridade | Absorção | Motivo | Forma no Brain |
|---|---|---|---|
| P0 | `SKILL.md` + frontmatter | Unidade simples e portátil | `SkillManifest` + loader |
| P0 | Carregamento em três níveis | Reduz contexto e mantém especialização | catálogo, corpo e recursos separados |
| P0 | Recursos executáveis separados | Permite automação sem colocar código no prompt | `SkillTool` executada no Sandbox |
| P0 | Descrição com gatilhos e não-gatilhos | Melhora roteamento e evita ativação excessiva | `trigger_examples`, `avoid_when` |
| P1 | `marketplace.json` | Distribuição agrupada | catálogo assinado e pinado |
| P1 | Convenção de referências por variante | Carregamento seletivo | resolver por domínio/versão |
| P1 | `skill-creator` | Evolução baseada em comparação | geração de Skill + evals |
| P1 | Anotações MCP | Descrever risco e semântica | `read_only`, `destructive`, `idempotent`, `open_world` |
| P2 | Adapters de harness | Portabilidade | exportadores para CLI, Android e MCP |

## 5. O que não absorver diretamente

Não incorporar o marketplace como autorização. Não copiar automaticamente `docx`, `pdf`, `pptx` ou `xlsx`, porque o README e os `LICENSE.txt` distinguem exemplos Apache de Skills de documento source-available/proprietárias. Não permitir `curl | bash` ou instalação de dependências sem pinagem. Não considerar instruções Markdown uma sandbox. Não carregar todas as referências para cada pedido.

## 6. Contratos propostos para o projeto atual

```text
SkillManifest {
  id, version, description, triggers, exclusions,
  body_path, resources, tools,
  required_permissions, network_policy,
  input_schema, output_schema,
  origin, license, sha256, trust_level
}

SkillActivation {
  run_id, skill_id, confidence, reason,
  resources_loaded, permissions_requested,
  approval_status
}

SkillToolResult {
  tool_call_id, status, structured_output,
  artifacts, stderr_redacted, duration_ms,
  provenance
}
```

A ativação deve registrar o hash do `SKILL.md`, das referências e do script utilizado. Se a Skill mudar, sessões futuras não devem reutilizar silenciosamente uma versão anterior.

## 7. Veredito

O repositório é uma referência forte para **extensibilidade, progressive disclosure e organização de conhecimento operacional**. O Brain deve absorver seu formato, seu carregamento progressivo, a separação entre instrução e recurso executável e o ciclo de criação/evaluação da `skill-creator`. Deve adicionar o que o repositório não fornece: autorização independente, isolamento, provenance, versionamento de sessão e política de risco.

## Referências

[1]: https://github.com/anthropics/skills "Anthropic Skills repository"
[2]: https://github.com/anthropics/skills/blob/main/README.md "Anthropic Skills README"
[3]: https://github.com/anthropics/skills/blob/main/.claude-plugin/marketplace.json "Anthropic Skills marketplace manifest"
[4]: https://github.com/anthropics/skills/blob/main/skills/skill-creator/SKILL.md "Skill creator workflow"
[5]: https://github.com/anthropics/skills/blob/main/skills/mcp-builder/SKILL.md "MCP builder skill"
[6]: https://github.com/anthropics/skills/blob/main/skills/docx/SKILL.md "DOCX skill"
[7]: https://github.com/anthropics/skills/blob/main/skills/webapp-testing/SKILL.md "Web application testing skill"
[8]: https://agentskills.io/specification "Agent Skills specification"
