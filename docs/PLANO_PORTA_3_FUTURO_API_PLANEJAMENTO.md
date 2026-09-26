# BrainCode — Plano Porta 3 Futuro: Planejamento via API

> Status: FUTURO
> Data: 2026-09-26
> Escopo: fluxo de planejamento da Porta 3 após Requirement Discovery e autorização do usuário.
> Regra: a API/LLM é aceleradora de planejamento; o Secretário continua sendo o orquestrador e autoridade do fluxo.

## Objetivo

Quando o usuário solicitar a criação de um projeto (ex.: um app IPTV), o Brain não deve iniciar a criação imediatamente.

Primeiro, o Secretário conduz o Requirement Discovery, tira dúvidas necessárias e consolida o contexto. Somente após o usuário autorizar a criação do plano, o Brain envia o ContextPack consolidado para a API/LLM.

A API/LLM deve produzir exatamente seis artefatos de planejamento:

1. PRD
2. TRD
3. App Flow
4. UI/UX Design
5. Backend Schema
6. Implementation Plan

Depois disso, o Secretário retoma o controle: valida os seis artefatos, organiza o plano em fases, divide as fases em tarefas, gera prompts específicos, resolve dependências e designa os especialistas/executores.

## Fluxo

Usuário → Secretário → Requirement Discovery → Perguntas/esclarecimentos → ContextPack consolidado → Usuário autoriza → API/LLM → seis artefatos → Secretário → validação → fases → tarefas → prompts → dependências → especialistas/executores → execução autorizada → Sandbox/Test/Critic → CI/E2E → revisão/entrega.

## Papel da API/LLM

A API/LLM recebe o contexto já consolidado e produz os seis artefatos. Ela não assume o controle do projeto.

A API não deve:
- decidir sozinha quando iniciar execução;
- liberar ferramentas;
- autorizar escrita no workspace;
- ignorar a Policy;
- substituir o Secretário;
- criar um segundo orquestrador.

O Secretário continua responsável por transformar o planejamento em execução controlada.

## Os seis artefatos

### 1. PRD
Define o produto e seus requisitos: objetivo, público, funcionalidades, requisitos funcionais e não funcionais, regras de negócio, restrições e critérios de aceitação.

### 2. TRD
Define a solução técnica: arquitetura, módulos, tecnologias, contratos, armazenamento, segurança, integrações, infraestrutura e decisões técnicas.

### 3. App Flow
Define a navegação e os estados do aplicativo: telas, transições, caminhos principais e alternativos, loading, estados vazios, erros e retornos.

### 4. UI/UX Design
Define o comportamento e a estrutura visual: telas, componentes, navegação, hierarquia visual, estados, acessibilidade, responsividade e regras de interação.

### 5. Backend Schema
Define dados e contratos: entidades, campos, relacionamentos, banco, índices, validações, contratos e interfaces/endpoints quando aplicáveis.

### 6. Implementation Plan
Transforma os cinco artefatos anteriores em execução: fases, tarefas, ordem, dependências, arquivos/módulos afetados, critérios de conclusão, testes, validação e entrega.

## Responsabilidade do Secretário após a API

1. Validar coerência entre os seis artefatos.
2. Detectar lacunas e contradições.
3. Separar o Implementation Plan em fases.
4. Dividir fases em tarefas.
5. Identificar dependências.
6. Gerar ou solicitar prompts específicos.
7. Designar especialistas/executores bounded.
8. Aplicar Policy e aprovações.
9. Acompanhar execução.
10. Encaminhar falhas para revisão.
11. Exigir testes, CI e E2E.
12. Registrar resultado e aprendizado.

## Regra de autorização

A criação não começa apenas porque a API terminou os seis documentos.

Sequência obrigatória:
Planejamento gerado → Secretário valida → Plano pronto → Autorização de execução → EXECUTION.

## Exemplo futuro: app IPTV

O usuário pode dizer: “Brain, vamos criar um app IPTV.”

O Secretário deve primeiro descobrir os requisitos necessários e fazer as perguntas relevantes. Depois que as dúvidas forem resolvidas, pode solicitar autorização para gerar o plano.

Após a autorização:
ContextPack → API/LLM → PRD + TRD + App Flow + UI/UX + Backend Schema + Implementation Plan → Secretário → validação → fases → tarefas → prompts → designação → execução.

## Relação com as 3 Portas

Este documento é uma extensão futura da Porta 3 — Criação / Desenvolvimento.

Não altera a separação atual das portas nem cria um segundo orquestrador.

Arquitetura desejada: Secretário → requisitos → autorização → API/LLM para planejamento → Secretário valida/orquestra → especialistas/executores → sandbox/testes/critic → CI/E2E → entrega.

## Regras arquiteturais

- API/LLM é opcional até o ponto em que o planejamento complexo exigir sua utilização.
- A chamada acontece depois do Requirement Discovery e da autorização para gerar o plano.
- O contexto enviado à API deve ser consolidado pelo Brain.
- O Secretário permanece como único agente de comunicação com o usuário.
- Policy continua sendo a autoridade.
- Nenhum artefato da API libera execução por si só.
- O plano deve ser persistido antes da execução.
- Falhas de implementação retornam ao ciclo de revisão.
- A execução continua sujeita a Sandbox, Test, Critic, CI e E2E.
- O fluxo deve preservar a possibilidade de operação local quando a tarefa puder ser resolvida sem API.

## Status

FUTURO — não implementar ainda.

Pré-condições principais: consolidação das Portas 1 e 2, camada Provider/API autorizada pela Policy e maturidade suficiente da infraestrutura de execução da Porta 3.