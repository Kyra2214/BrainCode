# BrainCode 2.4 — Garimpagem e Incorporação

Status atual: base de integração implementada; garimpagem de novos componentes é a próxima onda.

A 2.4 amplia capacidades sem criar segundo cérebro. Regra: BASE HOJE → APIs AMANHÃ → BRAIN DEPOIS.

Quando existir implementação open source madura e compatível, a preferência é preservar o motor original, seus testes e invariantes e integrar pela menor fronteira possível.

## Decisão por projeto

A — importação integral.
B — módulo completo.
C — componente externo/CLI/processo.
D — provider.
E — referência arquitetural.
F — descartado.

## Auditoria obrigatória

Licença/SPDX, commit/tag, arquitetura real, módulos, algoritmos, testes/CI, segurança, performance, memória, persistência, cache, dependências, compatibilidade Android/Linux/RootFS, credenciais, custo, termos de API, estratégia de integração, evidência, critérios de aceite e third-party notice.

Não copiar somente README, prompt ou nome de ferramenta.

## Integração

Intent → Requirements → ContextPack → Plan → Policy → Capability → Imported Component → Evidence → Verification → Critic → Revision/Fix → Readiness → Learning → Response.

O pipeline interno do componente importado pode permanecer intacto. O BrainCode controla o envelope externo.

## Preservação

Ao importar, preservar algoritmos, schemas, validações, erros, testes, fixtures, cache, retry, timeout, segurança e comportamento determinístico do upstream quando aplicável.

## Proveniência

Registrar projeto, licença, tag/commit, arquivos importados, modificações locais, dependências e testes. Manter a distinção UPSTREAM → ADAPTER/PATCH BRAINCОDE.

## Critério de integração concluída

Código + testes preservados + caller real + segurança + evidência + documentação + third-party notice.

Compilar não significa integrar.
