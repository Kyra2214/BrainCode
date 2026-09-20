# Roofts 0.6 — Camada Agent Skills do RooftS

Upstream: addyosmani/agent-skills.
Tag: 0.6.10.
Commit registrado no manifesto: c004a74784a08295d52749b4cda634125b9a581.
Asset: app/src/main/assets/roofts/0.6.
Runtime: /opt/roofts/0.6.
RooftS é a entidade única composta pelas camadas 0.3, 0.4, 0.5 e 0.6. As camadas 0.3–0.5 permanecem preservadas; esta documentação trata especificamente da camada 0.6 / Agent Skills. Consulte `docs/ROOFTS.md` para a definição da entidade completa.

Estado:
ROOFTS 0.6 / AGENT SKILLS = INSTALLED
SKILL RUNTIME INTEGRATION = NOT STARTED

Instalação dos arquivos não significa conectar SKILL.md ao Planner/Dispatcher/ActionGateway. O BrainCode usa conceitos compatíveis com Context Engineering, planning, testing, review e debugging sem criar um segundo runtime.

A instalação desta camada não constitui integração do RooftS com o BrainCode. Se a integração das Skills for aberta, cada Skill deve ser mapeada para Capability/SkillRegistry/Gates e atravessar o ciclo universal. Não criar SkillRuntime paralelo sem necessidade comprovada.

scripts/verify-roofts-06.sh é o verificador de conteúdo.
