# Roofts 0.6 — Camada Agent Skills do RooftS

Upstream: addyosmani/agent-skills.
Tag: 0.6.10.
Commit registrado no manifesto: c004a74784a08295d52749b04cda634125b9a581.
Asset: app/src/main/assets/roofts/0.6.
Runtime: /opt/roofts/0.6.
RooftS é a entidade única composta pelas camadas 0.3, 0.4, 0.5 e 0.6. As camadas 0.3–0.5 permanecem preservadas; esta documentação trata especificamente da camada 0.6 / Agent Skills. Consulte `docs/ROOFTS.md` para a definição da entidade completa.

Estado:
ROOFTS 0.6 / AGENT SKILLS = INSTALLED
SKILL RUNTIME INTEGRATION = LAZY CATALOG + ACTIVATION PLAN + SELECTOR FILTER INTEGRATED

Instalação dos arquivos não significa conectar SKILL.md ao Planner/Dispatcher/ActionGateway. O caller real de geração da Porta 3 agora usa catálogo lazy: o loader retém frontmatter, hash e origem, o selector aplica triggers e exclusões, o planner gera activation plan e o corpo só é lido para Skills aprovadas sem permissões pendentes. Recursos declarados podem ser lidos somente pelo resolver que exige caminho declarado e relativo. Isso não concede capability nem autorização.

A camada ainda não é um SkillRuntime paralelo. Manifestos Roofts são publicados no `SkillRegistry` como descoberta desabilitada. Qualquer capability, rede, segredo, recurso executável ou escrita continua subordinado ao caminho universal Capability/Policy/Gateway/Sandbox. O plano de ativação registra permissões pendentes e não as aprova implicitamente.

scripts/verify-roofts-06.sh é o verificador de conteúdo.

## Overlay de metadados BrainCode

`app/src/main/assets/roofts/0.6/braincode-skill-overlay.json` é um adaptador local separado dos arquivos upstream. Ele declara, por Skill, gatilhos em português, exclusões, recursos, permissões, capabilities, ferramentas e política de rede. O loader mescla o overlay somente quando o frontmatter do `SKILL.md` não fornece o campo; o frontmatter upstream prevalece. O `contentHash` continua sendo o SHA-256 do `SKILL.md` original, enquanto o hash do overlay é registrado separadamente como `overlayHash`.

**SHA-256 do overlay nesta versão:** `d1413b8ee66b92c22d4695b4a9c1b615643343b770345b8af7bd13e4a6262dda`.

O overlay não concede autorização nem executa scripts, hooks ou recursos. Ele serve apenas à descoberta/seleção local e à construção do plano de ativação; permissões pendentes continuam bloqueando o carregamento do corpo.
