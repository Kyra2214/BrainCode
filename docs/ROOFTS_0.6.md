# Roofts 0.6 — Estado de Integração

Upstream: addyosmani/agent-skills.
Tag: 0.6.10.
Commit registrado no manifesto: c004a74784a08295d52749b4cda634125b9a581.
Asset: app/src/main/assets/roofts/0.6.
Runtime: /opt/roofts/0.6.
Roofts 0.3–0.5 permanecem preservados.

Estado:
ROOFTS 0.6 = INSTALLED
SKILL RUNTIME INTEGRATION = NOT STARTED

Instalação dos arquivos não significa conectar SKILL.md ao Planner/Dispatcher/ActionGateway. O BrainCode usa conceitos compatíveis com Context Engineering, planning, testing, review e debugging sem criar um segundo runtime.

Se a integração das Skills for aberta, cada Skill deve ser mapeada para Capability/SkillRegistry/Gates e atravessar o ciclo universal. Não criar SkillRuntime paralelo sem necessidade comprovada.

scripts/verify-roofts-06.sh é o verificador de conteúdo.
