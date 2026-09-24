# Auditoria aprofundada — cporter202/software-income-playbooks

**Snapshot:** `8b07008c9fefcc5bbeb223e9d8ad5433271a9dcd` · **Arquivos:** 98 · **Fonte:** https://github.com/cporter202/software-income-playbooks

## Conclusão
O repo adiciona uma camada de decisão comercial sobre catálogos de API. Ele estrutura cliente, dor, MVP, API, monetização, tempo de construção, GTM, risco e adequação ao modelo SaaS/agência/serviço.

## Evidência direta
`playbooks/README.md` e 23 playbooks definem o formato. `apis/README.md` e 26 APIs fornecem perfis. `categories/` oferece mapas de oportunidade. `templates/` padroniza ideia, perfil de API, MVP e GTM. `resources/` apoia seleção e precificação. `archive/legacy-api-dump/` preserva material histórico, com categorias grandes como `jobs-apis-848`, `automation-apis-4825` e `lead-generation-apis-3452`.

## Padrão de decisão
A entrada é uma capacidade ou API; o processo produz hipótese de comprador, dor, oferta mínima, stack, modelo de preço, esforço e risco. A recomendação é validar com oferta/auditoria/monitoramento antes de construir dashboard. Isso é útil para o Brain como modo de análise, não como agente autônomo de negócio.

## Absorção
Criar `OpportunityCard` com `capability`, `buyer`, `pain`, `mvp`, `dependencies`, `unit_economics`, `validation_steps`, `risk`, `time_to_value` e `evidence`. Implementar scoring separado entre qualidade técnica, demanda, custo, risco jurídico e velocidade. Exigir que o resultado cite dados e marque hipóteses.

## Riscos
Material comercial pode conter links afiliados e não substitui validação de preço/termos. Não transformar score em promessa de receita. O arquivo `archive` deve permanecer fonte histórica, não catálogo ativo.

## Referências
[1]: https://github.com/cporter202/software-income-playbooks "Software Income Playbooks"
[2]: https://github.com/cporter202/software-income-playbooks/tree/main/templates "Playbook templates"
