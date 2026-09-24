# 03 — anthropics/skills

## Pente fino adicional

A principal descoberta operacional é que Skill é um **pacote carregável**, e não um texto gigante permanente. A estrutura permite combinar `SKILL.md` com scripts, referências, exemplos e recursos. Isso deve ser reproduzido conceitualmente no Braim.

### Skill como plugin de conhecimento

```text
Skill
├── metadata
├── SKILL.md
├── scripts/
├── references/
├── examples/
└── tests/
```

O Braim deve validar a Skill antes de permitir seu uso: schema, origem, licença, dependências, ferramentas requeridas e testes.

### Descoberta antes de carregamento

O catálogo deve manter apenas metadados baratos no contexto. O conteúdo completo entra somente depois do `SkillRanker` escolher a Skill. Isso reduz tokens e permite centenas de Skills.

### Skill ≠ memória

Skill é procedimento generalizável; experiência é evidência histórica. Uma experiência pode atualizar o score de uma Skill, mas não deve ser automaticamente transformada em instrução sem validação.

### Licença por recurso

O repositório mistura recursos com diferentes condições. O minerador do Braim deve registrar licença por Skill/recurso, nunca assumir que todo conteúdo do repositório pode ser copiado.

## Objetivo

Estudar o padrão Agent Skills e transformar o que for útil em uma arquitetura de Skills própria do Braim.

## Conceito central

Uma Skill é uma pasta autocontida com `SKILL.md`, instruções, recursos e eventualmente scripts. O modelo carrega esse conhecimento dinamicamente quando a tarefa exige. Isso resolve um problema crítico do Braim: não colocar conhecimento de centenas de domínios no contexto permanente.

## Estrutura mínima

O template oficial usa frontmatter YAML com pelo menos:

```yaml
---
name: minha-skill
description: Quando e para que esta skill deve ser usada.
---
```

O restante é instrução operacional.

## O que absorver

### Skill como unidade de conhecimento operacional

No Braim, Skill deve representar “como fazer algo”, não simplesmente “o que é algo”.

Exemplos:

- `criar-app-iptv`;
- `auditar-api-key`;
- `publicar-youtube`;
- `gerar-apk`;
- `corrigir-build-gradle`;
- `pesquisar-api-gratuita`.

### Carregamento sob demanda

O Brain deve resolver Skills por capacidade e carregar apenas as necessárias para aquele passo.

### Recursos associados

Uma Skill pode carregar scripts, templates, exemplos, schemas e referências. Isso é mais poderoso que um prompt gigante.

### Metadados

Além de `name` e `description`, o Braim deve acrescentar internamente:

- versão;
- autor/origem;
- licença;
- capabilities;
- tools;
- pré-requisitos;
- score;
- número de usos;
- taxa de sucesso;
- última validação;
- compatibilidade de ambiente.

### Skills como memória procedural

Memória registra experiências. Skill registra procedimento reutilizável. São coisas diferentes.

```text
Memory = o que aconteceu
Skill  = como fazer
Agent  = quem executa
Tool   = com o que executa
```

## Ferramentas interessantes demonstradas

As Skills do repositório incluem exemplos para documentos, PDF, planilhas, apresentações, desenvolvimento, pesquisa, MCP e tarefas especializadas. O valor para o Braim está no padrão de encapsulamento, não em importar automaticamente todas as Skills.

## Alerta de licença

O README diferencia Skills open source das Skills de documentos que são source-available/proprietárias. Por exemplo, `docx` e `pdf` indicam licença proprietária. Portanto, **não devemos copiar essas Skills para dentro do Braim sem verificar os termos individuais**.

Podemos estudar comportamento e arquitetura; código deve ser tratado conforme licença específica.

## Sistema de Skills proposto

```text
skills/
  iptv/
    SKILL.md
    scripts/
    templates/
    examples/
    tests/
    metadata.json
```

`metadata.json` pode guardar dados que não precisam entrar no prompt.

## Resolver

O Brain deve ter:

```text
SkillRegistry
SkillDiscovery
SkillRanker
SkillLoader
SkillValidator
SkillVersionManager
```

Fluxo:

```text
Intent
 ↓
required capabilities
 ↓
SkillRegistry
 ↓
rank por relevância + histórico
 ↓
carregar Skill
 ↓
executar
 ↓
registrar resultado
```

## O que não absorver

- Marketplace específico da Anthropic.
- Dependência de Claude.
- comandos `/plugin` como arquitetura interna;
- Skills proprietárias;
- comportamento específico do Claude Code.

## Prioridade

**CRÍTICA.** Deve influenciar diretamente a Fase E de Skills do Braim.

## Fontes

- https://github.com/anthropics/skills
- https://github.com/anthropics/skills/blob/main/README.md
- https://github.com/anthropics/skills/blob/main/template/SKILL.md
- https://github.com/anthropics/skills/blob/main/skills/docx/SKILL.md
- https://github.com/anthropics/skills/blob/main/skills/pdf/SKILL.md

## Conclusão

O Braim deve ter um **formato próprio, compatível conceitualmente com Agent Skills**, mas com memória de desempenho, licença, versão, requisitos e score. O pente fino reforça que Skill deve ser um pacote validável e versionável, com carregamento progressivo e execução determinística separada do LLM.
