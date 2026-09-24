# Catálogo de Skills do Braim

Este catálogo define as skills como capacidades declarativas e versionadas. Uma skill descreve o que pode ser feito; não concede autorização. A execução passa pelo PolicyBroker.

## Princípios

- Skill não é agente e não é prompt.
- Skill não concede permissão por existir no catálogo.
- Código de skill de terceiros é não confiável até validação.
- Toda skill declara risco, permissões, necessidade de sandbox, entradas, saídas e validações.
- Skills devem ser versionáveis, desativáveis e auditáveis.
- O catálogo pode crescer sem alterar o núcleo do Braim.

## Categorias previstas

- development
- research
- orchestration
- quality
- security
- memory
- workflow
- language
- data
- optimization
- reliability
- media
- vision
- speech
- integration
- operations

## Ciclo de vida

`discover → validate → register → activate → execute → validate_result → observe → update/disable`

## Segurança

Instalação/ativação deve validar manifesto, versão, hash, compatibilidade, licença, permissões declaradas e resultado do scanner de segurança. A decisão final de execução pertence ao PolicyBroker.

## Expansão futura

O catálogo deve receber skills especializadas de análise de código, segurança, pesquisa, APIs, QA, documentação, workflows, multimídia, visão, voz e operações conforme as capacidades reais forem implementadas.