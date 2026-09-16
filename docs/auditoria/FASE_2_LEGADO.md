# Auditoria BrainCode — Fase 2: legado não utilizado

**Snapshot de código:** `ffed42a4fd83095797dfd6bf53213a82b1c8788d`
**Escopo:** distinguir legado arquitetural de código apenas não alcançado pela UI.

## 1. Regra de classificação

Um arquivo não é legado somente porque não aparece no caminho principal. Para ser classificado como legado, deve haver evidência de que pertence a uma arquitetura abandonada, duplicada ou substituída.

Classificação usada:

- **ATUAL** — parte do desenho atual e/ou chamado por produção.
- **PARCIAL** — existe, mas ainda há integração faltante.
- **LEGADO** — pertence a desenho abandonado ou substituído.
- **REFERÊNCIA** — material preservado para estudo, não runtime.
- **ÓRFÃO** — código atual sem caller, mas ainda sem prova suficiente para remoção automática.

## 2. Legado IaBrain

A documentação de decisões registra a remoção dos snapshots copiados do IaBrain que antes ficavam em `reference/iabrain/skills-tools/`, incluindo entidades/DAOs, Room, registry original, parser e políticas copiadas.

No HEAD atual, `reference/iabrain` ainda existe, porém a árvore encontrada contém apenas material de referência/README, não a implementação Android copiada que foi descartada.

Conclusão: **REFERÊNCIA**, não runtime. Não deve ser confundida com dependência do BrainCode.

## 3. Referência Python

`reference/braincode-python` existe como árvore de referência. Não há evidência de que o APK Android carregue classes desse diretório.

Conclusão: **REFERÊNCIA**.

## 4. Arquitetura local-LLM antiga

A arquitetura atual explicitamente não usa engine local de LLM como cérebro obrigatório do Chat. A base RootFS também mantém Ollama fora da imagem e trata-o como plugin sob demanda.

Portanto qualquer documentação ou código histórico que trate um servidor LLM local como caminho obrigatório do Chat é legado conceitual.

No HEAD analisado não foi encontrada prova de que um engine local antigo seja entrypoint do Chat Android.

Conclusão: o conceito antigo é **LEGADO**, enquanto suporte opcional a Ollama continua sendo capacidade separada.

## 5. Agent autônomo com LLM próprio

O contrato atual usa Agents bounded. O Agent recebe missão/capabilities/policy e não substitui o Brain como autoridade.

Portanto implementações históricas que criassem um Agent com objetivo próprio e modelo obrigatório devem ser consideradas incompatíveis com a arquitetura atual.

Não há evidência nesta fase de um arquivo atual específico que precise ser apagado por esse motivo; portanto não foi marcada remoção automática.

## 6. Caminho de comando como cérebro

`ComandosCatalogo.kt` não é legado: o catálogo atual serve como UX de prompt e autocomplete. O código deixa explícito que comandos operacionais reais têm prioridade em `SandboxViewModel.submitThreadInput()`.

O legado é o desenho antigo em que slash commands eram o cérebro da execução. No HEAD atual eles são apenas uma entrada para o Brain ou um atalho operacional delimitado.

## 7. Duplicação de UI antiga

A principal sobra concreta identificada é o cluster dentro de `MainActivity.kt`:

- `ToolsAndApiScreen`
- `SandboxValidationScreen`
- `OperationsScreen`
- `ChatboxSection` como dependência da tela de validação

Esses elementos não têm caller de produção identificado. Porém o histórico mostra que partes dessas telas foram absorvidas pela UI atual de Thread/Settings.

Classificação: **ÓRFÃO com forte indício de legado de UI**, mas a remoção definitiva deve ser uma alteração separada, depois de confirmar que nenhum teste, variante ou navegação indireta depende delas.

## 8. RootFS antigo / camada única

Existe histórico de invalidação do cache legado de RootFS de camada única. O desenho atual usa três perfis homologados:

```text
0.3.3 base
   ↓
0.4.1 agent-extra
   ↓
0.5.0 agent-android
```

O código atual não deve reintroduzir um cache ou manifesto de uma única camada como autoridade.

## 9. Scripts de fabricação x runtime

`rootfs-builder/` contém scripts de fabricação e migração. Eles não são chamados pelo APK em runtime. Isso não é defeito: são ferramentas de build/release.

Assim:

- `build.sh`, Dockerfiles e scripts de migração = **BUILD TOOLING**;
- manifests em `app/src/main/res/raw` = **RUNTIME INPUT**;
- assets de catálogo = **RUNTIME INPUT**;
- releases `.tar.gz` = **DISTRIBUIÇÃO**.

Não classificar scripts de build como órfãos apenas porque o Android não os executa.

## 10. Documentação histórica

Arquivos como `AUDITORIA_PESADA.md`, documentos de sessões antigas, planos históricos e relatórios de auditorias anteriores são rastreabilidade. Eles não são dependências de compilação nem runtime.

Eles devem permanecer enquanto ajudarem a explicar decisões, mas não podem vencer o código atual em caso de divergência.

## 11. Resultado da Fase 2

### Legado conceitual confirmado

1. snapshot Android/Room/DAO copiado do IaBrain como implementação;
2. LLM local obrigatório como cérebro do Chat;
3. Agent autônomo com LLM/objetivo próprio;
4. slash command como autoridade central;
5. RootFS legado de camada única.

### Código atual a tratar como candidato de limpeza

- cluster de telas órfãs em `MainActivity.kt`.

### Não remover

- `reference/*` sem transformar referência em runtime;
- `rootfs-builder/*`, porque é infraestrutura de fabricação;
- manifests e catálogos atuais;
- Brain/Android runtime apenas por não terem caller na UI principal.

## 12. Regra para as fases seguintes

Fases 3–5 devem verificar se os RootFS homologados contêm ferramentas ou scripts que correspondam a componentes órfãos/legados das fases 1 e 2. A análise de arquivo compactado precisa ser distinguida da análise dos Dockerfiles: se o binário `.tar.gz` não puder ser aberto no ambiente de auditoria, isso será declarado como limitação, nunca convertido em suposição.
