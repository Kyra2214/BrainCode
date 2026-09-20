# RooftS — Entidade e Camadas

**Status:** arquitetura consolidada  
**Data:** 2026-09-20  
**Repositório:** `Kyra2214/BrainCode`

## 1. Identidade canônica

**RooftS é uma única entidade de infraestrutura/runtime.**

As versões `0.3`, `0.4`, `0.5` e `0.6` não são quatro RooftS diferentes. Elas são **camadas/componentes pertencentes ao mesmo RooftS**.

Modelo canônico:

```
RooftS
├── camada 0.3
├── camada 0.4
├── camada 0.5
└── camada 0.6 — Agent Skills
```

A consolidação é **conceitual e arquitetural**. Os artefatos físicos continuam separados quando essa separação for necessária para preservar integridade, proveniência, atualização e compatibilidade.

## 2. Responsabilidade das camadas

| Camada | Papel | Regra |
|---|---|---|
| 0.3 | camada RootFS existente | preservar como artefato homologado |
| 0.4 | camada RootFS existente | preservar como artefato homologado |
| 0.5 | camada RootFS existente | preservar como artefato homologado |
| 0.6 | Agent Skills / agentes / workflows auxiliares | preservar como camada de comportamento e skills; não representa o RooftS inteiro |

A camada 0.6 não deve ser descrita como “o RooftS”. Ela é somente **RooftS 0.6 / Agent Skills**.

## 3. Regra de preservação

Esta consolidação **não** significa:

- apagar 0.3, 0.4 ou 0.5;
- reconstruir os RootFS homologados;
- copiar conteúdo de uma camada para outra;
- substituir os manifests existentes;
- fundir fisicamente todas as árvores em um único artefato;
- ativar automaticamente o runtime das Skills 0.6;
- integrar 0.6 ao Planner/Dispatcher/ActionGateway nesta fase.

Os artefatos físicos permanecem independentes onde isso for necessário.

A entidade lógica é única:

```
RooftS = { 0.3, 0.4, 0.5, 0.6 }
```

## 4. Estado físico atual

As camadas RootFS 0.3–0.5 são materializadas pelo fluxo existente de três arquivos/artefatos RootFS.

A camada 0.6 é instalada separadamente no guest em:

```
/opt/roofts/0.6
```

e vem do asset:

```
app/src/main/assets/roofts/0.6
```

A instalação de 0.6 sobre o RootFS existente é, portanto, uma **composição de camadas do mesmo RooftS**, e não a instalação de um produto independente chamado “RooftS 0.6”.

## 5. 0.6 — identidade específica

A camada 0.6 atualmente corresponde ao upstream:

- repositório: `addyosmani/agent-skills`
- tag: `0.6.10`
- commit importado: `c004a74784a08295d52749b4cda634125b9a581`

Ela contém skills, agents, commands, hooks, references, docs, scripts e evals.

**Importante:** presença física da camada 0.6 não significa integração das Skills com o runtime do BrainCode.

A integração futura deverá reutilizar:

```
Skill / Agent
    ↓
Capability
    ↓
Policy
    ↓
ActionGateway
    ↓
Execution
    ↓
Verification / Critic / Readiness
```

Não criar um segundo orquestrador ou um segundo runtime de execução para as Skills sem necessidade arquitetural comprovada.

## 6. Fronteira com o BrainCode

O BrainCode é o orquestrador.

O RooftS é uma entidade de infraestrutura composta por camadas.

A camada 0.6 fornece material de Agent Skills, mas não recebe autoridade para:

- autorizar capabilities;
- ignorar Policy;
- executar diretamente fora do ActionGateway;
- criar um segundo Brain;
- substituir o Planner;
- substituir o CapabilityRegistry;
- substituir o sistema de evidência;
- substituir memória ou Readiness.

Portanto:

```
BrainCode
   ↓
RooftS
   ├── 0.3
   ├── 0.4
   ├── 0.5
   └── 0.6 Agent Skills
```

é a relação conceitual desta fase.

## 7. Terminologia obrigatória

Usar:

- **RooftS** — entidade completa;
- **RooftS 0.3** — camada 0.3;
- **RooftS 0.4** — camada 0.4;
- **RooftS 0.5** — camada 0.5;
- **RooftS 0.6 / Agent Skills** — camada 0.6.

Evitar:

- “RooftS 0.6 é o RooftS”;
- “instalar o RooftS 0.6” quando o significado for apenas adicionar a camada Agent Skills;
- tratar 0.3–0.6 como produtos independentes;
- declarar integração BrainCode ↔ RooftS somente porque os arquivos estão presentes.

## 8. Escopo desta consolidação

Esta alteração estabelece somente:

1. RooftS como entidade única;
2. 0.3–0.6 como suas camadas;
3. preservação física e semântica das camadas;
4. 0.6 explicitamente como camada Agent Skills;
5. terminologia e documentação canônicas;
6. fronteira conceitual RooftS ↔ BrainCode.

**Não faz parte desta alteração:** integração das Skills 0.6 ao Planner, SkillRegistry, Dispatcher ou ActionGateway.

Essa integração será uma etapa posterior e deverá ser implementada sobre a entidade RooftS consolidada.

## 9. Critério de aceitação

A consolidação está correta quando:

- [ ] existe uma definição canônica de RooftS;
- [ ] 0.3, 0.4, 0.5 e 0.6 aparecem como camadas da mesma entidade;
- [ ] os artefatos antigos permanecem preservados;
- [ ] o manifest específico do 0.6 continua intacto;
- [ ] nenhuma Skill 0.6 é ativada por esta mudança;
- [ ] nenhum Planner/Dispatcher/ActionGateway é alterado para integrar 0.6;
- [ ] documentação de arquitetura deixa de tratar 0.6 como entidade independente;
- [ ] a UI/terminologia deixa claro que 0.6 é a camada Agent Skills;
- [ ] build, testes e E2E continuam verdes.

**Regra desta fase: uma entidade, quatro camadas; nenhuma integração comportamental nova.**
