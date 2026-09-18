# ROOFTS 0.6 — Installation Report

## Estado antes

As versões anteriores permanecem preservadas e não foram sobrescritas, movidas ou renomeadas:

| Versão | Estado |
|---|---|
| Roofts 0.3 (`rootfs-v0.3.3`) | Preservado |
| Roofts 0.4 (`rootfs-agent-v0.4.1`) | Preservado |
| Roofts 0.5 (`rootfs-agent-android-v0.5.0`) | Preservado |

O mecanismo existente continua baixando e extraindo as três camadas RootFS na ordem base → extra → Android/API. O Roofts 0.6 é um payload independente e não altera os manifests, releases ou tarballs dessas camadas.

## Origem

| Campo | Valor |
|---|---|
| Repository | `addyosmani/agent-skills` |
| Commit | `c004a74784a08295d52749b04cda634125b9a581` |
| Tag | `0.6.10` |
| Import date | `2026-09-18` |
| Status | `INSTALLED_NOT_INTEGRATED` |

O commit e a tag foram obtidos diretamente do repositório upstream clonado. O conteúdo completo foi importado, sem restringir a cópia apenas a `SKILL.md`.

## Conteúdo importado

A contagem foi derivada dos arquivos efetivamente presentes no payload:

| Componente | Arquivos |
|---|---:|
| Skills | 30 |
| Agents | 4 |
| References | 7 |
| Docs | 16 |
| Hooks | 8 |
| Scripts | 13 |
| Commands | 9 |
| Evals | 75 |
| Arquivos totais | 195 |

Também foram preservados README, LICENSE, AGENTS.md, configurações de plugins, arquivos de integração para diferentes clientes e demais arquivos auxiliares do upstream. O diretório `.git` do repositório upstream não é distribuído como parte do payload; a identidade Git é registrada no manifesto.

## Localização e instalação

O payload está versionado em:

```text
app/src/main/assets/roofts/0.6/
app/src/main/assets/roofts/0.6.manifest.json
```

Durante `AndroidSandboxFactory.prepareRuntime`, depois que as três camadas RootFS existentes são validadas e materializadas, o app copia o asset completo para:

```text
/opt/roofts/0.6
```

A cópia é recriada a cada preparação do runtime, garantindo que o conteúdo do APK seja o conteúdo disponibilizado ao RootFS. O procedimento não modifica nenhum arquivo das camadas 0.3, 0.4 ou 0.5; apenas acrescenta o namespace independente `/opt/roofts/0.6`.

O APK empacota o conteúdo por meio do mecanismo normal de `app/src/main/assets`. O runtime convidado acessa os arquivos pelo caminho absoluto acima, sem que as Skills sejam carregadas ou executadas pelos agentes.

## Integridade

O manifesto específico registra versão, origem, commit, tag, data, contagens e hashes SHA-256 de `README.md`, `LICENSE` e `AGENTS.md`. A verificação reproduzível está em:

```bash
./scripts/verify-roofts-06.sh
```

A verificação atual confirma 195 arquivos, os diretórios principais, o manifesto, a licença, os hashes e o commit upstream registrado.

## Estado da integração

```text
ROOFTS 0.6 = INSTALLED
SKILL INTEGRATION = NOT STARTED
```

Esta etapa não cria `SkillRuntime`, `SkillRegistry`, `SkillExecution`, `SkillResult`, `SkillContext` ou `SkillGate`. Também não conecta Skills a Agent, Dispatcher, ActionGateway, Capability, Planner, WorkflowEngine ou qualquer outro ciclo de execução. O comportamento dos agentes permanece inalterado.

## Validação planejada e critério de parada

O fluxo normal de validação deve confirmar compilação, testes, lint, build e APK. A validação dos RootFS anteriores continua usando seus manifests e artefatos publicados, sem rebuild. Depois que o APK for gerado e os testes forem concluídos, a implementação desta parte deve parar. Auditoria, mapeamento e integração nativa das Skills pertencem à Parte 2.
