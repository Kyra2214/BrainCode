# Checklist Mestre — E2E Físico BrainCode / Sandbox

Objetivo: validar, a partir da instalação do APK, que o BrainCode está realmente montado no runtime: instalado, descoberto, registrado, autorizado, executável e observável.

## 0. Antes de instalar
- [ ] Confirmar commit do APK.
- [ ] Confirmar CI correspondente.
- [ ] Registrar versão/commit do APK.
- [ ] Confirmar que não há alterações locais não planejadas.
- [ ] Confirmar ponto de restauração `auditoria-ponto-de-restauracao-20260924`.
- [ ] Guardar logs da auditoria.
- [ ] Não alterar código durante a primeira rodada.

## 1. Instalação física do APK
- [ ] Instalar APK.
- [ ] Confirmar instalação sem erro.
- [ ] Abrir aplicativo.
- [ ] Confirmar ausência de crash na inicialização.
- [ ] Confirmar criação/carregamento do ambiente local.
- [ ] Confirmar permissões necessárias.
- [ ] Confirmar assets necessários.
- [ ] Confirmar `prompts_library.sql` disponível.
- [ ] Confirmar que não há dependência obrigatória de download externo para iniciar.
- [ ] Confirmar logs de inicialização.
- [ ] Testar operação offline quando aplicável.

## 2. Integridade do Sandbox
- [ ] `BrainSandboxController` inicializado.
- [ ] `PolicyBroker` inicializado.
- [ ] `CapabilityRegistry` inicializado.
- [ ] `CapabilityResolver` inicializado.
- [ ] `ActionGateway` inicializado.
- [ ] `AgentRegistry` carregado quando aplicável.
- [ ] `SkillRegistry` carregado.
- [ ] Memory disponível.
- [ ] EventStore disponível.
- [ ] ExecutionTrace disponível.
- [ ] SandboxPlatform disponível.
- [ ] Workspace disponível.
- [ ] Scheduler/workflows disponíveis quando aplicável.
- [ ] Nenhum componente crítico falhou silenciosamente.
- [ ] Nenhum componente obrigatório ficou `null`, `UNAVAILABLE` ou equivalente sem justificativa.

## 3. Inventário de Skills
Para cada skill: instalada -> descoberta -> registrada -> disponível -> autorizada -> executor -> execução -> resultado.
- [ ] Contar skills instaladas.
- [ ] Comparar com inventário esperado.
- [ ] Confirmar Roofts 0.3.
- [ ] Confirmar Roofts 0.4.
- [ ] Confirmar Roofts 0.5.
- [ ] Confirmar Roofts 0.6.
- [ ] Confirmar as 25 skills do Roofts 0.6.
- [ ] Confirmar manifestos.
- [ ] Confirmar IDs e metadados.
- [ ] Confirmar discovery.
- [ ] Confirmar registro.
- [ ] Confirmar disponibilidade.
- [ ] Confirmar ativação.
- [ ] Confirmar executor.
- [ ] Confirmar policy.
- [ ] Confirmar caminho real até execução.

## 4. Tools
- [ ] Inventariar todas as tools.
- [ ] Comparar código, registry e runtime.
- [ ] Tool instalada.
- [ ] Tool descoberta.
- [ ] Tool registrada.
- [ ] Definição válida.
- [ ] Executor existente.
- [ ] Executor conectado.
- [ ] Policy reconhece a tool.
- [ ] CapabilityResolver encontra a tool.
- [ ] ActionGateway recebe a ação.
- [ ] Sandbox executa.
- [ ] Resultado retorna.
- [ ] Identificar toda tool registrada sem executor.
- [ ] Identificar toda tool registrada mas inalcançável.

## 5. Capabilities
Fluxo: Capability -> Registry -> Resolver -> Policy -> ActionGateway -> Executor -> Sandbox -> Resultado.
- [ ] Inventariar capabilities.
- [ ] Confirmar `CapabilityDefinition`.
- [ ] Confirmar `CapabilityRegistry`.
- [ ] Confirmar discovery.
- [ ] Confirmar resolver.
- [ ] Confirmar provider.
- [ ] Confirmar executor.
- [ ] Confirmar policy.
- [ ] Confirmar ActionGateway.
- [ ] Executar pelo menos uma capability ponta a ponta.

## 6. Policy / segurança
### Permitido
- [ ] Ação autorizada passa pela Policy.
- [ ] Capability válida é resolvida.
- [ ] ActionGateway executa.
- [ ] Resultado retorna.

### Negado
- [ ] Capability inexistente é negada.
- [ ] Tool não autorizada é negada.
- [ ] Skill não autorizada é negada.
- [ ] Ação fora da allowlist é negada.
- [ ] Não existe bypass do Policy Layer.
- [ ] Deny-by-default funciona.

## 7. ExecutionTrace / EventStore
- [ ] `runId` criado.
- [ ] Trace criado.
- [ ] Action registrada.
- [ ] Policy decision registrada.
- [ ] Executor registrado.
- [ ] Resultado registrado.
- [ ] EventStore recebeu eventos.
- [ ] Trace pode ser recuperado.
- [ ] Não existe armazenamento paralelo desnecessário.
- [ ] Falhas também são registradas.

## 8. PromptLibrary
- [ ] `prompts_library.sql` existe no APK.
- [ ] Loader encontra o asset.
- [ ] Loader inicializa.
- [ ] Biblioteca carrega sem OOM.
- [ ] Biblioteca carrega sem timeout.
- [ ] Quantidade carregada é coerente.
- [ ] IDs SQL são preservados.
- [ ] Categorias funcionam.
- [ ] Tags funcionam.
- [ ] Busca funciona.
- [ ] Prompt pode ser recuperado.
- [ ] Prompt existente pode ser reutilizado.
- [ ] Skill relacionada é preservada.
- [ ] Nenhum JSON antigo participa do fluxo.
- [ ] Não existe download obrigatório.
- [ ] Biblioteca funciona offline.
- [ ] Verificar a solução definitiva adotada para o problema de memória do loader.

## 9. Porta 1 — Chat / Secretário
### Conversa simples
- [ ] `Olá`.
- [ ] Pergunta factual simples.
- [ ] Pergunta contextual.
- [ ] Pergunta ambígua.
- [ ] Pergunta conhecida pela KnowledgeMemory.
- [ ] Resposta conhecida é recuperada sem fluxo desnecessário.
- [ ] Miss local não vaza fallback interno ao usuário.

### Pesquisa
- [ ] Pergunta desconhecida dispara pesquisa automaticamente.
- [ ] Não exige aprovação do usuário.
- [ ] WebResearch executa.
- [ ] Resultado chega ao Conversation Engine.
- [ ] Conversation sintetiza.
- [ ] Secretário entrega resposta concreta.
- [ ] Não aparecem mensagens internas como “entendi o pedido” ou equivalentes.

### Memória
- [ ] Resposta válida pode ser salva.
- [ ] Proveniência preservada.
- [ ] Próxima consulta reutiliza conhecimento válido.
- [ ] Conhecimento externo não vira verdade automaticamente sem validação.

## 10. Porta 2 — Prompt
### Reutilização
- [ ] Pedido de prompt é entendido.
- [ ] PromptLibrary é consultada.
- [ ] Prompt compatível é encontrado.
- [ ] Prompt existente é reutilizado.
- [ ] Não é criada duplicata desnecessária.

### Pesquisa/criação
- [ ] Se não houver prompt adequado, pesquisa é acionada.
- [ ] Resultado é analisado.
- [ ] Prompt é estruturado.
- [ ] Validação ocorre.
- [ ] Prompt é integrado corretamente.
- [ ] Biblioteca permanece consistente.
- [ ] IDs e metadados são preservados.

## 11. Porta 3 — Criação
- [ ] Pedido de criação é entendido.
- [ ] Requirements são identificados.
- [ ] Ambiguidades são tratadas.
- [ ] Plano é criado.
- [ ] Aprovação ocorre quando necessária.
- [ ] Especialistas são selecionados.
- [ ] Capabilities correspondentes são encontradas.
- [ ] Policy autoriza.
- [ ] Tools/skills necessárias são encontradas.
- [ ] Workspace é criado.
- [ ] Execução ocorre no Sandbox.
- [ ] Testes são executados.
- [ ] Integração ocorre.
- [ ] Resultado final é produzido.
- [ ] Trace registra o processo.

## 12. Specialists
- [ ] `BuiltInAgentDefinitions` carregado.
- [ ] `SpecialistDefinitions` carregado.
- [ ] `SpecialistCapabilities` carregado.
- [ ] Specialists chegam ao `CapabilityRegistry`.
- [ ] `CreationWorkflowPlanner` consegue selecioná-los.
- [ ] Policy trata corretamente disponibilidade/autorização.
- [ ] Specialist sem executor não é apresentado falsamente como executável.
- [ ] Specialist executável possui caminho real até execução.

## 13. Roofts 0.6
Para cada uma das 25 skills:
- [ ] Arquivo presente.
- [ ] Manifest presente.
- [ ] Skill descoberta.
- [ ] Skill registrada.
- [ ] Recursos encontrados.
- [ ] Dependências resolvidas.
- [ ] Capability associada.
- [ ] Policy aplicada.
- [ ] Executor encontrado.
- [ ] Sandbox consegue executar.
- [ ] Resultado retorna.

Componentes a conferir:
- [ ] RequirementDiscovery.
- [ ] AssumptionManager.
- [ ] SelfCritic.
- [ ] KeywordPlanner.
- [ ] Dispatcher.
- [ ] `sandbox.code`.
- [ ] SafeSkillResourceExecutor.
- [ ] RooftsSkillActivation.
- [ ] RooftsSkillEvaluation.
- [ ] RooftsSkillManifestBridge.
- [ ] RooftsSemanticGrader.

## 14. WebResearch
- [ ] Agent descoberto quando aplicável.
- [ ] Provider disponível.
- [ ] Pesquisa executa.
- [ ] Resultado retorna.
- [ ] Proteção contra prompt injection funciona.
- [ ] Quality gate funciona.
- [ ] Provenance é preservada.
- [ ] Conversation recebe o resultado.
- [ ] Resposta final não expõe detalhes internos desnecessários.

## 15. Falhas e recuperação
Testar deliberadamente:
- [ ] Tool inexistente.
- [ ] Skill inexistente.
- [ ] Capability inexistente.
- [ ] Provider indisponível.
- [ ] Pesquisa sem resultado.
- [ ] Executor falhando.
- [ ] Policy negando.
- [ ] Timeout.
- [ ] Entrada inválida.
- [ ] Falha durante workflow.

Verificar:
- [ ] Sandbox não trava inteiro.
- [ ] Erro é registrado.
- [ ] Trace permanece íntegro.
- [ ] Usuário recebe resposta compreensível.
- [ ] Stack trace interno não vaza.
- [ ] Não cria estado inconsistente.

## 16. Offline-first
Com internet desligada quando possível:
- [ ] App inicia.
- [ ] Chat local funciona.
- [ ] KnowledgeMemory funciona.
- [ ] PromptLibrary funciona.
- [ ] Skills locais funcionam.
- [ ] Tools locais funcionam.
- [ ] Sandbox funciona.
- [ ] Porta 2 funciona para prompts existentes.
- [ ] Porta 3 funciona para operações locais.
- [ ] Pesquisa web falha de maneira controlada.
- [ ] Falha web não derruba o restante do Brain.

## 17. Observabilidade
- [ ] EventStore possui eventos.
- [ ] ExecutionTrace possui traces.
- [ ] ActionAuditLog possui ações.
- [ ] IDs permitem correlacionar execução.
- [ ] Erros são localizáveis.
- [ ] É possível descobrir por que uma ação foi permitida/negada.
- [ ] É possível descobrir qual skill/tool foi usada.
- [ ] É possível descobrir qual executor rodou.

## 18. Peças órfãs
Procurar especificamente:
- [ ] Classe sem caller.
- [ ] Skill sem registry.
- [ ] Tool sem registry.
- [ ] Tool sem executor.
- [ ] Capability sem provider.
- [ ] Provider sem rota.
- [ ] Agent sem integração.
- [ ] Specialist sem executor.
- [ ] Workflow sem executor.
- [ ] Registry sem consumidor.
- [ ] Executor sem capability.
- [ ] Capability sem caminho pelo ActionGateway.
- [ ] Código duplicado.
- [ ] Código substituído ainda ativo.
- [ ] Código legado carregado acidentalmente.
- [ ] Documentação afirmando comportamento que o runtime não executa.

## 19. Resultado final
Para cada componente, registrar:
- Instalado
- Registrado
- Descoberto
- Autorizado
- Executável
- Testado

Classificação:
- **PASS** — funcionando ponta a ponta.
- **PARTIAL** — funciona com limitação conhecida.
- **DISCONNECTED** — existe, mas não chega ao runtime.
- **BLOCKED** — existe, mas Policy/ambiente impede.
- **BROKEN** — deveria funcionar e falha.
- **NOT_TESTED** — ainda não foi exercitado.

### Regra de aprovação
Uma peça só é considerada realmente integrada quando houver evidência de:

**instalada -> descoberta -> registrada -> resolvida -> autorizada -> executada -> resultado -> trace**

E o Sandbox E2E só deve ser considerado aprovado quando:
- Porta 1 validada;
- Porta 2 validada;
- Porta 3 validada;
- inventário de Skills/Tools sem órfãos críticos;
- falhas conhecidas classificadas;
- evidências/logs preservados.
