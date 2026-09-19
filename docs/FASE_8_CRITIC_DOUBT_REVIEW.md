# BrainCode 2.3 — Fase 8: Critic / Doubt / Review

A Fase 8 generaliza a crítica para qualquer resultado por meio de `UniversalCritic`, comparando objetivo, requisitos, restrições, evidências e resultado.

## Regras

- resultado vazio gera finding bloqueante;
- requisito ausente gera finding de revisão;
- restrição `must:` não demonstrada gera bloqueio;
- mudança de alto risco sem evidência verificada gera bloqueio;
- resultado completo e sem findings passa.

`DoubtDrivenReview` converte findings em `RevisionDecision`: aceita, revisa ou aborta. Mudanças de alto risco não podem ser aceitas apenas por declaração textual.

A crítica é independente de prompt e pode ser utilizada para código, pesquisa, execução, integração e planejamento. Não concede autorização nem substitui Policy.

## Validação

A suíte `:brain` passou com casos de requisito ausente, resultado vazio, alto risco sem evidência e resultado completo.

## Próximo módulo

A Fase 9 deve implementar o Readiness Gate com os sete estágios obrigatórios e Definition of Done por tipo de tarefa.
