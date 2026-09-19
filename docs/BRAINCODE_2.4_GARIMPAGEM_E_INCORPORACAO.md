# BrainCode 2.4 — Fase de Garimpagem, Importação e Incorporação Integral de Capacidades

Status: PLANEJAMENTO — executar somente depois do fechamento verificável da Fase 2.3.
Escopo: BrainCode inteiro, não um agente específico e não apenas o Prompt Creator.

## 1. PROPÓSITO

A Fase 2.3 fecha o ciclo comportamental universal do BrainCode:

ENTENDER → DESCOBRIR REQUISITOS → RESOLVER AMBIGUIDADE → PLANEJAR → AUTORIZAR → EXECUTAR → OBSERVAR/EVIDÊNCIA → VERIFICAR → CRITICAR → CORRIGIR/REPETIR → READINESS/DoD → RESPONDER → APRENDER.

A Fase 2.4 não substitui esse ciclo. Ela amplia aquilo que o ciclo consegue executar.

O objetivo não é acumular links, criar uma lista de ideias ou escrever versões simplificadas de ferramentas existentes.

O objetivo é identificar software open source que já resolva problemas difíceis, estudar o código real, verificar licença, arquitetura, testes, dependências, segurança e limitações, e então trazer para o BrainCode aquilo que for tecnicamente justificável.

REGRA PRINCIPAL:

Quando existir uma implementação open source madura que resolve corretamente um problema que precisamos resolver, não devemos automaticamente reescrever uma versão simplificada dela. Devemos avaliar a possibilidade de importar a implementação real, preservando seu comportamento e testes, e colocar o BrainCode ao redor dela por meio de uma fronteira de integração mínima e explícita.

A adaptação deve ocorrer na fronteira entre o componente importado e o runtime do BrainCode, e não destruindo o motor original para recriá-lo em outra forma.

---

## 2. REGRA FUNDAMENTAL: IMPORTAR IMPLEMENTAÇÃO, NÃO APENAS IDEIA

A Fase 2.4 não deve repetir este padrão:

1. encontrar um projeto;
2. olhar somente o README;
3. gostar de uma funcionalidade;
4. descrever a funcionalidade;
5. implementar outra coisa parecida no BrainCode;
6. abandonar detalhes importantes do projeto original.

Esse procedimento não deve ser a estratégia padrão.

Quando um projeto for selecionado para incorporação, a auditoria deve estudar o código-fonte, arquitetura, módulos, interfaces, modelos de dados, pipelines, algoritmos, testes, fixtures, tratamento de erros, segurança, cache, persistência, configuração, CLI, APIs, mecanismos de fallback, observabilidade, limites conhecidos, problemas abertos relevantes, histórico de manutenção, releases/tags e compatibilidade com Android/Linux/RootFS quando aplicável.

Se o componente puder ser incorporado integralmente, a preferência é pela incorporação integral.

Se somente um submódulo puder ser incorporado de maneira tecnicamente correta, importar o submódulo completo, incluindo seus testes e dependências necessárias.

Se o projeto depender de serviço externo pago, separar claramente software open source, serviço externo, credencial, custo, provider e fallback local.

Se a licença não permitir a incorporação pretendida, não copiar o código. Registrar a descoberta e procurar outra implementação compatível.

---

## 3. PRINCÍPIO BASE HOJE / APIS AMANHÃ / BRAIN DEPOIS

A Fase 2.4 deve preservar:

BASE HOJE → APIS AMANHÃ → BRAIN DEPOIS.

Uma capacidade pode começar usando implementação local, CLI, biblioteca, binário, API pública, serviço externo, MCP ou provider especializado.

Isso não significa que o BrainCode ficará preso ao mecanismo utilizado hoje.

Quando houver necessidade de abstração, a integração deve ocorrer por Capability/Provider Gateway.

Exemplo conceitual:

KeywordResearchCapability
→ implementação local
→ provider Google
→ provider Search Console
→ provider DataForSEO
→ provider alternativo
→ futuro mecanismo nativo do Brain.

O Brain não deve conhecer detalhes desnecessários de cada provider.

Ao mesmo tempo, não criar abstrações artificiais antes de existir necessidade real.

REGRA:

Preservar o motor real e criar a menor fronteira necessária para o Brain controlá-lo.

---

## 4. O QUE A 2.4 NÃO DEVE FAZER

Não deve:

- criar dezenas de Skills isoladas apenas para aumentar o número de Skills;
- criar agentes especializados sem necessidade;
- substituir o ciclo universal da 2.3;
- criar um segundo orquestrador;
- criar um segundo caminho de execução;
- criar um segundo sistema de memória sem justificativa;
- criar um segundo sistema de observabilidade sem justificativa;
- duplicar código já existente no Brain;
- copiar somente README;
- copiar apenas prompts;
- copiar somente nomes de ferramentas;
- transformar cada repositório externo em uma implementação Kotlin diferente;
- criar wrappers profundos que escondam o comportamento do projeto importado;
- eliminar testes do projeto importado sem justificativa;
- remover mecanismos de segurança para facilitar integração;
- substituir implementação determinística por LLM quando o projeto original já possui algoritmo determinístico;
- substituir uma implementação completa por uma versão mínima apenas porque a versão mínima é mais fácil;
- declarar integração concluída apenas porque o código compila.

---

## 5. CRITÉRIO DE SELEÇÃO

Todo candidato deve passar por auditoria antes de qualquer importação.

A auditoria deve responder:

### 5.1 Licença

Registrar:

- licença SPDX quando disponível;
- arquivo de licença;
- compatibilidade com BrainCode;
- obrigações de atribuição;
- obrigações de distribuição;
- copyleft quando aplicável;
- dependências com licenças incompatíveis;
- código sem licença identificável;
- assets com licença diferente;
- modelos com termos próprios;
- APIs com termos próprios.

Nenhum código deve ser incorporado sem licença compatível ou decisão explicitamente registrada.

### 5.2 Saúde do projeto

Verificar:

- atividade recente;
- releases;
- commits;
- issues;
- pull requests;
- testes;
- CI;
- documentação;
- dependências;
- compatibilidade de versões;
- sinais de abandono;
- vulnerabilidades conhecidas;
- breaking changes;
- estabilidade das APIs.

Estrelas do GitHub não são critério suficiente.

### 5.3 Qualidade técnica

Verificar:

- arquitetura;
- separação de responsabilidades;
- testabilidade;
- tratamento de erros;
- segurança;
- concorrência;
- performance;
- consumo de memória;
- persistência;
- cache;
- isolamento;
- determinismo;
- portabilidade;
- extensibilidade.

### 5.4 Valor real para o BrainCode

Responder:

1. Que problema difícil o projeto resolve?
2. O BrainCode já resolve esse problema?
3. Se já resolve, qual implementação é mais completa?
4. O projeto contém código que seria caro ou demorado reimplementar?
5. O código é determinístico quando deveria ser?
6. Existem testes que comprovam o comportamento?
7. Possui integração que o BrainCode ainda não possui?
8. Pode funcionar como Capability?
9. Pode funcionar como Provider?
10. Pode funcionar como componente interno do Sandbox?
11. Precisa ficar externo ao APK?
12. Pode rodar no RootFS?
13. Pode rodar localmente?
14. Depende de internet?
15. Depende de credenciais?
16. Depende de serviço pago?
17. Qual é a menor fronteira necessária para integrá-lo?

---

## 6. CLASSIFICAÇÃO DE INCORPORAÇÃO

Cada projeto deve receber uma decisão técnica, não um ranking.

### A — IMPORTAÇÃO INTEGRAL

Usar quando:

- resolve problema necessário;
- licença permite;
- implementação é madura;
- testes são úteis;
- arquitetura pode ser preservada;
- dependências são aceitáveis;
- integração não exige destruir a estrutura original.

Nesse caso, importar o máximo possível do componente real.

### B — IMPORTAÇÃO DE MÓDULO COMPLETO

Quando o projeto inteiro for grande ou contiver partes que não pertencem ao Brain, mas um módulo autocontido possuir valor real.

Importar o módulo completo, com código, testes, fixtures, configuração, documentação necessária e dependências.

### C — EXECUÇÃO COMO COMPONENTE EXTERNO

Quando o projeto deve continuar como processo, CLI, serviço ou runtime independente.

O Brain controla entrada, autorização, execução, timeout, sandbox, evidência, saída e verificação.

O componente continua sendo o componente original.

### D — PROVIDER

Quando o projeto fornece acesso a serviço externo.

O provider fica atrás da arquitetura existente de Provider Gateway/Capability.

### E — REFERÊNCIA ARQUITETURAL

Usar somente quando a licença ou a arquitetura não permite incorporar código, mas ideias técnicas podem ser estudadas e implementadas independentemente.

Essa categoria é exceção quando já existir código reutilizável compatível.

### F — DESCARTADO

Registrar motivo:

- licença;
- baixa qualidade;
- abandono;
- dependências ruins;
- redundância;
- incompatibilidade;
- risco;
- custo;
- ausência de valor.

---

## 7. INTEGRAÇÃO COM O CICLO UNIVERSAL

Nenhum componente importado deve criar caminho paralelo que escape do Brain.

Fluxo externo:

INTENT → REQUIREMENT → PLAN → POLICY → CAPABILITY → COMPONENT → EVIDENCE → VERIFICATION → CRITIC → REVISION/FIX → READINESS → LEARNING → RESPONSE.

Se uma ferramenta externa possuir pipeline interno, esse pipeline deve ser preservado.

O BrainCode deve controlar o ciclo externo.

Exemplo: "Faça uma auditoria SEO deste site."

O Brain deve:

1. entender intenção;
2. descobrir URL e requisitos;
3. verificar ambiguidades;
4. planejar;
5. autorizar acesso de rede;
6. selecionar Capability;
7. executar crawler/auditor;
8. preservar evidências;
9. verificar execução;
10. criticar resultados;
11. corrigir/reexecutar quando necessário;
12. verificar Readiness;
13. registrar aprendizado;
14. responder.

O componente SEO não deve virar agente soberano que responde diretamente ignorando o Brain.

---

## 8. PRESERVAÇÃO DO COMPORTAMENTO ORIGINAL

Quando um projeto for importado, preservar:

- algoritmos;
- regras;
- heurísticas;
- modelos;
- schemas;
- formatos;
- validações;
- tratamento de erros;
- testes;
- fixtures;
- limites;
- configuração;
- mecanismos de segurança;
- cache;
- retry;
- timeout;
- logging;
- fallback.

Não trocar automaticamente:

- crawler por regex;
- banco por arquivo simples;
- algoritmo por prompt;
- testes por mocks;
- processamento local por API;
- mecanismo determinístico por LLM.

A integração deve preservar o que já funciona.

---

## 9. TESTES

Um componente importado não está integrado somente porque compilou.

Preservar ou criar:

- testes unitários;
- integração;
- contrato;
- segurança;
- regressão;
- comportamento;
- E2E quando aplicável.

Executar antes da integração, quando possível:

1. testes originais;
2. testes BrainCode;
3. testes de contrato;
4. testes de integração;
5. E2E;
6. verificação de que comportamento original não foi alterado.

Se teste original precisar ser alterado, documentar:

- teste;
- motivo;
- mudança;
- risco;
- substituição;
- evidência.

Não apagar teste apenas para fazer integração passar.

---

## 10. SEGURANÇA

Toda importação deve passar pelo modelo de segurança do BrainCode.

Especialmente:

- crawlers;
- downloaders;
- execução de código;
- subprocessos;
- rede;
- arquivos;
- URLs;
- scraping;
- credenciais;
- APIs;
- MCP;
- plugins;
- binários.

Avaliar:

- SSRF;
- DNS rebinding;
- path traversal;
- command injection;
- shell injection;
- execução arbitrária;
- upload/download malicioso;
- ZIP malicioso;
- symlink;
- permissões;
- memória;
- CPU;
- processos;
- descritores;
- disco;
- rede;
- isolamento.

Nenhuma ferramenta importada deve receber acesso maior que uma Capability nativa equivalente.

---

## 11. OBSERVABILIDADE

Cada execução deve possuir correlação com a execução universal.

Registrar, quando aplicável:

- runId;
- taskId;
- stepId;
- attempt;
- capabilityId;
- componentId;
- providerId;
- início;
- fim;
- duração;
- status;
- erro;
- evidências;
- artefatos;
- saída;
- verificação;
- decisão do critic;
- revisão;
- readiness.

Objetivo:

usuário → intenção → requisitos → plano → policy → capability → componente → execução → evidência → verificação → critic → correção → readiness → aprendizado → resposta.

Uma execução não pode depender de logs soltos para ser reconstruída.

---

## 12. EVIDÊNCIA E PROVENIÊNCIA

Componentes de pesquisa, SEO, scraping, análise ou geração de dados devem preservar origem.

Quando aplicável:

- URL;
- timestamp;
- fonte;
- método;
- endpoint;
- parâmetros relevantes;
- hash do conteúdo;
- versão do componente;
- provider;
- resposta bruta ou referência segura;
- transformação realizada;
- resultado derivado.

A camada Evidence deve distinguir:

- dado observado;
- inferência;
- recomendação;
- opinião;
- resultado de ferramenta;
- resultado de modelo.

---

## 13. PRIMEIRA ONDA DE GARIMPAGEM

A primeira onda deve incluir, sem limitar-se a:

### 13.1 SEO / Web Intelligence

Investigar profundamente:

- Claude SEO;
- CrawlSEO;
- Trivium;
- OpenSEO;
- serpIQ;
- outros projetos relevantes encontrados durante a auditoria.

Estudar código real.

Procurar:

- crawler;
- keyword research;
- Search Console;
- Core Web Vitals;
- auditoria técnica;
- Schema;
- sitemap;
- robots;
- GEO/AEO;
- análise semântica;
- clustering;
- backlinks;
- MCP;
- coleta de evidências;
- relatórios;
- regras determinísticas.

Meta: aumentar capacidades do Brain, não criar "um agente SEO" isolado.

### 13.2 Vídeo / Multimídia

Investigar projetos de:

- transcrição;
- tradução;
- dublagem;
- legendas;
- landscape para vertical;
- cortes;
- thumbnails/capas;
- metadata;
- Shorts/Reels/TikTok;
- pipelines locais.

Projetos como ZastTranslate devem ser avaliados para a Fábrica de Vídeos 2.0.

Separar:

BrainCode = orquestração e inteligência.

Fábrica de Vídeos = domínio de execução multimídia.

Quando fizer sentido, o Brain controla capacidades da Fábrica em vez de duplicá-las.

### 13.3 Pesquisa e agentes

Continuar auditoria dos repositórios já levantados anteriormente.

Priorizar:

- research;
- evidence;
- planning;
- task decomposition;
- agent runtime;
- skill systems;
- memory;
- context engineering;
- verification;
- debugging;
- self-critique;
- evaluation;
- source-driven development.

### 13.4 Social/media APIs

Investigar:

- YouTube;
- TikTok;
- Instagram;
- Facebook;
- X;
- Reddit;
- LinkedIn;
- outras plataformas relevantes.

Separar:

- API oficial;
- SDK;
- scraping;
- automação de navegador;
- serviço externo;
- autenticação;
- limites;
- termos de uso;
- risco de bloqueio.

Não incorporar scraping automaticamente só porque existe código open source.

---

## 14. MÉTODO DE AUDITORIA POR REPOSITÓRIO

Para cada candidato criar relatório contendo:

### Identificação

- nome;
- URL;
- autor/organização;
- licença;
- linguagem;
- versão;
- commit/tag analisado;
- data da análise.

### Arquitetura

Descrever:

- entrypoints;
- módulos;
- fluxo principal;
- dados;
- armazenamento;
- execução;
- dependências;
- integrações;
- extensões.

### Implementação

Identificar arquivos e módulos que realmente implementam a funcionalidade.

Não basta citar diretórios.

### Testes

Registrar:

- quantidade aproximada;
- tipos;
- cobertura quando disponível;
- CI;
- fixtures;
- testes críticos.

### Segurança

Registrar riscos e controles existentes.

### Integração

Determinar:

- integral;
- módulo;
- externo;
- provider;
- referência;
- descarte.

### Plano

Definir:

- arquivos;
- dependências;
- localização;
- interface Brain;
- Capability;
- Provider;
- Sandbox;
- testes;
- documentação;
- licenças/notices.

### Critérios de aceite

Definir como saberemos que a integração funciona.

---

## 15. THIRD-PARTY NOTICES E PROVENIÊNCIA

Toda importação deve atualizar documentação de terceiros.

Registrar:

- projeto;
- versão/commit;
- licença;
- copyright;
- arquivos importados;
- modificações locais;
- dependências relevantes.

Não esconder código de terceiros dentro de arquivos renomeados como se fosse original do BrainCode.

Quando houver modificações:

UPSTREAM → MUDANÇAS BRAINCОDE.

Isso deve permitir atualização futura.

---

## 16. ESTRATÉGIA DE ATUALIZAÇÃO

Para cada componente importado registrar:

- upstream;
- commit;
- tag;
- patch local;
- dependências;
- testes;
- incompatibilidades conhecidas.

Preferir:

upstream component → minimal integration layer → BrainCode.

Evitar:

upstream component → copiar tudo → modificar profundamente → perder capacidade de atualizar.

"Importar inteiro" significa preservar o máximo possível do software construído e testado, não modificar indiscriminadamente.

---

## 17. CAPABILITY REGISTRY

Toda funcionalidade incorporada que possa ser acionada pelo Brain deve possuir identidade explícita.

Registrar:

- capabilityId;
- descrição;
- entrada;
- saída;
- pré-condições;
- risco;
- policy necessária;
- recursos;
- rede;
- arquivos;
- credenciais;
- provider;
- componente;
- evidência;
- método de verificação;
- critérios de aceitação;
- readiness.

O CapabilityRegistry não deve conter somente nomes.

Planner e Router precisam saber o que uma capacidade realmente faz.

---

## 18. SKILLS NÃO SUBSTITUEM CAPABILITIES

Skill descreve comportamento/workflow.

Capability fornece ação real.

Provider fornece implementação/serviço.

Componente externo fornece motor especializado.

Critic avalia.

Readiness decide se pode concluir.

Modelo:

Skill → comportamento
Capability → ação
Provider → implementação/serviço
Component → motor
Critic → avaliação
Readiness → conclusão

Não transformar uma biblioteca inteira em Skill.

---

## 19. EXEMPLO COMPLETO: SEO

Usuário:

"Analisa meu site e me fala o que está ruim."

Brain deve:

1. interpretar intenção;
2. pedir URL se ausente;
3. descobrir se o usuário quer SEO técnico, conteúdo, performance ou análise geral;
4. planejar;
5. autorizar rede;
6. escolher crawler;
7. executar;
8. coletar evidências;
9. verificar se terminou;
10. verificar se páginas correspondem ao escopo;
11. analisar resultados;
12. critic;
13. corrigir/reexecutar se houver inconsistência;
14. Readiness;
15. produzir relatório;
16. registrar aprendizado.

Crawler continua sendo motor de crawler.

Brain continua sendo cérebro.

---

## 20. EXEMPLO COMPLETO: VÍDEO

Usuário:

"Pega esse vídeo de 40 minutos e transforma em conteúdo para TikTok."

Brain deve:

1. identificar vídeo;
2. descobrir objetivo;
3. identificar idioma;
4. determinar se deve traduzir;
5. definir formato;
6. planejar transcrição;
7. transcrever;
8. verificar transcrição;
9. identificar trechos;
10. gerar cortes;
11. converter formato;
12. gerar legenda;
13. gerar metadata/capa quando solicitado;
14. verificar arquivos;
15. verificar duração e formato;
16. critic;
17. corrigir;
18. Readiness;
19. entregar artefatos.

Se pipeline externo já possuir implementação completa, não reescrever dezenas de chamadas artificiais apenas para dizer que foi integrado.

---

## 21. ESTRATÉGIA LOCAL-FIRST

Classificar cada componente:

- totalmente local;
- local com modelo externo;
- local com API opcional;
- remoto obrigatório;
- híbrido.

Preferência:

1. local;
2. gratuito;
3. API opcional;
4. provider pago quando necessário.

O Brain deve conseguir usar implementação local hoje e trocar provider amanhã.

Nenhuma integração externa deve virar dependência obrigatória sem necessidade.

---

## 22. CUSTO

Para cada componente registrar:

- custo de software;
- custo de API;
- custo de modelo;
- armazenamento;
- infraestrutura;
- limites gratuitos;
- dependência de chave.

Nunca classificar como gratuito apenas porque o GitHub é open source.

---

## 23. PERFORMANCE

Avaliar:

- CPU;
- RAM;
- armazenamento;
- tempo;
- paralelismo;
- downloads;
- tamanho de modelos;
- cache;
- Android;
- servidor;
- RootFS.

Classificar quando necessário como:

- server-only;
- optional;
- external component;
- futura integração.

Não simplificar silenciosamente um componente poderoso apenas para fazê-lo caber.

---

## 24. ROOTFS E ANDROID

Quando adequado para execução local, avaliar:

- RootFS;
- Roofts 0.3;
- Roofts 0.4;
- Roofts 0.5;
- Roofts 0.6;
- binário;
- Python;
- Node;
- Java/Kotlin;
- processo externo;
- sandbox.

Roofts 0.3, 0.4, 0.5 e 0.6 devem ser preservados.

A incorporação de novos componentes não deve alterar manifests existentes sem necessidade.

Roofts 0.6 continua sendo o pacote completo de Agent Skills importado anteriormente.

---

## 25. INTEGRAÇÃO COMPLETA

Uma integração só pode ser considerada completa quando:

- código está presente;
- licença está registrada;
- dependências resolvidas;
- testes originais passam ou exceções documentadas;
- Capability registrada;
- Policy integrada;
- execução passa pelo caminho universal;
- evidências capturadas;
- resultado verificável;
- Critic consegue avaliar;
- correção/reexecução funciona quando necessária;
- Readiness consegue bloquear resultado inválido;
- observabilidade possui correlação;
- E2E cobre o caso;
- CI valida;
- documentação atualizada;
- build real valida quando aplicável.

Compilar não é integração completa.

Teste unitário passar não é integração completa.

Agente chamar ferramenta não é integração completa.

---

## 26. COMPONENTES QUE JÁ POSSUEM AGENTE

Se projeto externo possuir agente próprio, não assumir automaticamente que esse agente deve substituir o Brain.

Auditar separadamente:

- agente;
- ferramentas;
- memória;
- planner;
- prompts;
- runtime;
- executor;
- critic;
- testes.

Se runtime for realmente superior e compatível, avaliar importação integral.

Se ferramentas forem úteis, importar ferramentas.

Se algoritmo for útil, importar módulo.

A decisão deve ser baseada no código real.

---

## 27. PROJETOS COM MCP

MCP é mecanismo de integração, não arquitetura obrigatória do Brain.

Quando houver MCP, avaliar:

- implementação das ferramentas;
- schemas;
- autenticação;
- transporte;
- segurança;
- estado;
- erros.

Se ferramentas forem úteis, Brain pode expô-las como Capabilities.

Não criar camada MCP desnecessária apenas porque o projeto possui MCP.

---

## 28. DUPLICAÇÃO INTERNA

Antes de importar qualquer módulo, pesquisar o BrainCode.

Verificar se já existe:

- Capability equivalente;
- Provider equivalente;
- crawler;
- executor;
- memory;
- research;
- evidence;
- planner;
- critic;
- readiness;
- observability;
- sandbox.

Se existir, comparar.

Não manter duas implementações equivalentes sem motivo.

Comparar:

- cobertura;
- testes;
- segurança;
- extensibilidade;
- compatibilidade;
- manutenção;
- custo.

Documentar decisão.

---

## 29. ORDEM DE EXECUÇÃO

### 2.4.0 — Baseline

Confirmar:

- 2.3 fechada;
- CI;
- testes;
- E2E;
- APK;
- ciclo universal;
- correção/reexecução;
- readiness;
- learning.

Sem isso, não iniciar grandes importações.

### 2.4.1 — Inventário

Reunir todos os repositórios já descobertos em conversas e auditorias anteriores.

Não perder candidatos antigos.

### 2.4.2 — Auditoria

Auditar código real, licença, testes, arquitetura, segurança e dependências.

### 2.4.3 — Classificação

Classificar cada projeto em A/B/C/D/E/F.

### 2.4.4 — Primeiro componente

Selecionar componente de alto valor e realizar integração completa como prova.

### 2.4.5 — Validação

Executar testes originais + Brain + E2E.

### 2.4.6 — Segunda onda

Somente após primeira integração estabilizada, importar próximos componentes.

### 2.4.7 — Consolidação

Eliminar duplicações e formalizar Capability/Provider Registry.

### 2.4.8 — Auditoria final

Verificar:

- terceiros;
- licenças;
- segurança;
- testes;
- observabilidade;
- performance;
- custos;
- APK;
- RootFS;
- CI;
- documentação.

---

## 30. DEFINIÇÃO DE PRONTO DA 2.4

A Fase 2.4 não termina quando vários repositórios foram adicionados.

Termina quando o BrainCode demonstrar que consegue incorporar software externo de forma reproduzível.

Deve existir pelo menos:

1. auditoria documentada de múltiplos candidatos;
2. licença verificada;
3. classificação de incorporação;
4. pelo menos uma integração completa de alto valor;
5. código upstream preservado quando possível;
6. testes upstream preservados;
7. integração com Capability Registry;
8. integração com Policy;
9. execução pelo ciclo universal;
10. evidência;
11. verificação;
12. critic;
13. correção/reexecução;
14. readiness;
15. learning;
16. observabilidade correlacionada;
17. E2E;
18. CI;
19. documentação de terceiros;
20. validação Android/RootFS quando aplicável.

---

## 31. PRINCÍPIO FINAL

O BrainCode não deve tentar reinventar sozinho tudo que existe no ecossistema open source.

Também não deve virar um depósito de wrappers.

A 2.4 deve construir um sistema capaz de reconhecer software excelente, verificar sua qualidade, preservar sua implementação, incorporar o que é compatível e colocá-lo sob controle do ciclo cognitivo e operacional do BrainCode.

Arquitetura:

OPEN SOURCE MADURO
→ AUDITORIA REAL
→ LICENÇA + SEGURANÇA + TESTES
→ IMPORTAÇÃO INTEGRAL / MÓDULO / EXTERNO / PROVIDER
→ CAPABILITY REGISTRY
→ BRAIN PLANNER
→ POLICY
→ EXECUTION
→ EVIDENCE
→ VERIFICATION
→ CRITIC
→ FIX / REEXECUTE
→ READINESS
→ LEARNING

REGRA OPERACIONAL:

Não reimplementar por reflexo aquilo que já existe. Primeiro procurar, auditar e tentar preservar a implementação real. Adaptar somente a fronteira necessária para o BrainCode controlá-la.

A Fase 2.4 deve aumentar a capacidade do BrainCode sem fragmentar seu cérebro.

---

## 32. ORIENTAÇÃO PARA O IMPLEMENTADOR

Antes de alterar código:

1. ler este documento inteiro;
2. confirmar que 2.3 está fechada;
3. localizar documentos de arquitetura atuais;
4. localizar CapabilityRegistry;
5. localizar Provider Gateway;
6. localizar Policy;
7. localizar execução universal;
8. localizar Evidence;
9. localizar Verification;
10. localizar Critic;
11. localizar FixVerifyLearn;
12. localizar Readiness;
13. localizar Learning;
14. localizar observabilidade;
15. localizar E2E.

Depois:

- auditar repositórios;
- produzir relatórios;
- escolher componentes;
- só então implementar.

Não criar código baseado somente em README.

Não declarar sucesso sem evidência.

Não apagar testes para fazer integração passar.

Não criar caminho paralelo.

Não transformar esta fase em projeto de "mais agentes".

Esta fase é uma operação de aquisição, validação, preservação e incorporação de capacidades reais para o BrainCode.

FIM DA ESPECIFICAÇÃO BRAINCОDE 2.4.
