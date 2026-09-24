# Capability

Contrato declarativo para uma capacidade universal do BrainCode.

A implementação canônica está em `com.brain.capability.CapabilityDefinition`, no módulo `:brain`. Uma capability pode ser fornecida por API, provider, agent, skill, tool, command, sandbox, workflow ou sistema interno.

O modelo exige identidade (`id`, `name`, `description`), categoria, owner/origem, parâmetros e schemas, capabilities requeridas/fornecidas, permissões, risco, custo, latência estimada, confiabilidade, suporte a arquivos/web/código/reasoning, disponibilidade, versão, status e proveniência.

O registro canônico é `com.brain.capability.CapabilityRegistry`. O registry consulta metadados e produz candidatos estruturais; ele **não** autoriza nem executa. Autorização continua no `PolicyBroker`, e a execução autorizada deve passar pelo `ActionGateway`; o Android usa `CicloExecucaoPlano` como executor do plano autorizado.

`ApiCatalog` permanece como catálogo operacional especializado de provider/modelo e métricas de runtime. `SkillRegistry` permanece como registro especializado de manifests, assinaturas e revogações. Ambos devem publicar/adaptar metadados no registry universal quando suas integrações forem consolidadas, sem criar uma segunda autoridade global.
