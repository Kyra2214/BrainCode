# Auditoria Técnica BrainCode — 8 fases

Auditoria executada sobre o HEAD `ffed42a4fd83095797dfd6bf53213a82b1c8788d`.

A regra da auditoria foi **código como fonte da verdade**. Documentação anterior foi tratada como evidência histórica/pista, não como prova de integração.

## Partes

1. `FASE_1_ORFAOS_WIRING.md` — arquivos órfãos, callers e wiring real.
2. `FASE_2_LEGADO.md` — código/arquitetura legada e referência que não pertence ao runtime.
3. `FASE_3_ROOTFS_0.3.3.md` — RootFS base, ferramentas e relação com órfãos/legado.
4. `FASE_4_ROOTFS_0.4.1.md` — Agent Extra e relação com a cadeia base.
5. `FASE_5_ROOTFS_0.5.0.md` — Agent Android/API e relação com as duas camadas anteriores.
6. `FASE_6_UI.md` — o que a UI realmente chama e quais capacidades chegam por composer/slash.
7. `FASE_7_CATALOGO_FERRAMENTAS.md` — inventário de toolchains, plugins, binários, comandos e capabilities.
8. `FASE_8_ARVORE_E_SIMULADO_COMPLETO.md` — árvore ponta a ponta e simulação desde instalação até entrega.

## Achados centrais

- Existe um cluster concreto de UI órfã em `MainActivity.kt`: `ToolsAndApiScreen`, `SandboxValidationScreen` e `OperationsScreen`; `ChatboxSection` é órfã transitiva.
- A maior parte da funcionalidade dessas telas já foi absorvida pela Thread/Settings e pelos comandos operacionais.
- `FastIntentClassifier`, `KeywordPlanner`, `KeywordFunctionSplitter`, `DurableJobRunner`, `CapabilityDiscovery`, `Dispatcher` e `ActionGateway` possuem wiring de produção no HEAD auditado.
- `skills_catalog.json` e `explorer_china_seed.json` estão ligados ao runtime atual.
- Os três RootFS formam uma cadeia 0.3.3 → 0.4.1 → 0.5.0. O Dockerfile base declara `default-jdk` e `gradle`; ausência de Java em runtime deve ser investigada na materialização/PATH/seleção.
- `BuiltInCatalog.tools` está vazio porque ferramentas gerais são fornecidas pelo RootFS; plugins opcionais são separados.
- O `/deliver` atual gera `DeliveryReceipt` com hashes/telemetria; o código auditado não cria ZIP automaticamente.
- `GitManager` possui commit/push, mas a UI principal auditada expõe status/diff e não um fluxo dedicado de commit/push.

## Limitação explícita

Os três assets RootFS são tarballs binários com mais de 1 GB. A auditoria conseguiu verificar fonte de fabricação, manifests, release metadata e hashes publicados, mas não extraiu os tarballs binários nesta interface. Não foi inventada uma listagem interna de arquivos.

## Regra pós-auditoria

Não apagar código somente porque foi marcado como órfão. A próxima limpeza deve:

1. remover ou consolidar a UI órfã após confirmação de que não existe navegação oculta;
2. revisar APIs Git sem caller atual;
3. adicionar E2E observável no aparelho/emulador;
4. só então remover legado com commit separado e teste de regressão.
