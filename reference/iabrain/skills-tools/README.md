# IaBrain — referência arquitetural encerrada

O BrainCode consultou o IaBrain como **fonte de ideias arquiteturais**, não como dependência.

A antiga cópia de código, catálogos, schemas Room e testes foi removida desta árvore porque já cumpriu seu objetivo: comparar conceitos e adaptar somente o que fazia sentido ao BrainCode.

## O que foi aproveitado

### 1. Registry orientado a capability

A principal contribuição foi a ideia de um registry que descreve candidatos por capacidade e atributos de adequação, como qualidade, velocidade, custo, confiabilidade e contexto.

No BrainCode isso foi generalizado para o `CapabilityRegistry`/`CapabilityDiscovery`, sem manter o modelo `IA → comando → Room` do IaBrain.

### 2. Policy antes da execução

A ideia de validar função, capacidade, parâmetros e risco antes da execução inspirou o `PolicyBroker` do BrainCode.

O BrainCode possui um contrato mais amplo: actor → capability → parâmetros → risco → autorização → executor → evidência.

### 3. Resolução declarativa

O IaBrain mostrou o valor de separar definição de comando/capacidade da resolução do executor. No BrainCode, comandos não são o centro da arquitetura: o centro é a capability.

### 4. Proveniência e roteamento

A associação entre capacidade e candidato de execução foi mantida como conceito, mas o BrainCode não importa os catálogos de IAs do IaBrain nem usa `iaRecomendada` como autoridade.

## O que foi deliberadamente descartado

- Room `AppDatabase`/Entities/DAOs do IaBrain;
- schemas históricos Room;
- `IACapabilityRegistry` original;
- `RoomCommandResolver` e resolvers dependentes;
- `SlashCommandParser` como núcleo da interação;
- catálogos de IAs/APIs copiados;
- testes que dependiam dos packages/modelos do IaBrain;
- qualquer execução ou import automático desses artefatos.

## Regra

**BrainCode não é um fork do IaBrain.**

Quando uma ideia externa é útil, ela deve ser expressa no contrato e no modelo nativo do BrainCode. O código de origem não deve ser mantido apenas para "ter referência" quando já não houver uma necessidade concreta de auditoria.
