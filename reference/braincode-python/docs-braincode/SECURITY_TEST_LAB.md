# Security Test Lab — Validação Adversarial do Braim

## Objetivo

O Security Test Lab é uma camada de segurança dedicada a testar projetos produzidos pelo Brain/Code antes da entrega. Ele tenta encontrar caminhos de falha de forma controlada, reproduzível e rastreável.

O objetivo é **validar e endurecer o sistema**, não transformar o Braim em uma ferramenta de ataque contra terceiros.

## Fluxo

```text
Projeto gerado
    ↓
ProjectScanner
    ↓
Security Test Lab
    ↓
Attack Simulation
    ↓
Sandbox isolado
    ↓
Policy / Detection / QA
    ↓
Evidence Engine
    ↓
Fix → Verify → Learn
    ↓
ReadinessGate
```

## Modos

### Scanner

Análise estática e estrutural sem execução de payloads adversariais.

### Simulation

Execução de cenários controlados contra o workspace do projeto, fixtures, mocks e Sandbox autorizado.

### Adversarial

Agentes especializados tentam descobrir bypasses dentro do ambiente controlado.

### Red Team → Blue Team

Um agente adversarial tenta violar uma fronteira definida; PolicyBroker, Sandbox, QA e demais defesas devem detectar, bloquear e registrar o comportamento.

## Classes iniciais de teste

- prompt/context injection;
- exposição de API keys e credentials;
- bypass de Policy/capability;
- path traversal;
- symlink escape;
- command injection;
- SSRF e abuso de rede;
- escape de processo/Sandbox;
- skill maliciosa ou não confiável;
- tampering de dependências e supply chain;
- resource exhaustion/DoS controlado;
- race condition e TOCTOU;
- authentication/authorization bypass;
- abuso de webhook e inputs;
- secrets em logs, memória, eventos e artefatos;
- manipulação de resultados e evidências;
- falhas de recovery, idempotência e anti-replay.

Os cenários devem testar **controles**, não depender de ataques contra infraestrutura externa real.

## Fronteiras obrigatórias

O Lab deve respeitar as mesmas fronteiras do runtime:

- PolicyBroker continua sendo a autoridade;
- Sandbox é obrigatório para execução adversarial;
- CredentialVault fornece apenas referências e injeção controlada;
- EventStore registra as transições;
- Evidence Engine registra evidências;
- ReadinessGate decide se os critérios de segurança foram satisfeitos.

Uma simulação não pode desabilitar esses controles para produzir um resultado aparentemente mais realista. Se um cenário conseguir contornar uma dessas fronteiras, isso deve ser registrado como vulnerabilidade.

## Alvos permitidos

Por padrão, somente:

- código do próprio projeto sob teste;
- workspace temporário criado para o teste;
- fixtures;
- mocks;
- serviços locais de teste;
- Sandbox explicitamente autorizado.

Alvos externos ficam bloqueados por padrão. Qualquer exceção futura deverá possuir escopo explícito, autorização verificável e ambiente apropriado de teste.

## Evidência por cenário

Cada execução deve produzir, quando aplicável:

```text
attack_id
target
vector
preconditions
policy_decision
sandbox_profile
input_hash
observed_behavior
defenses_triggered
events
artifacts
result
severity
evidence
remediation
regression_test
```

Secrets reais nunca devem ser usados como fixtures. Valores sintéticos devem ser suficientes para testar detecção de vazamento, redaction e boundary enforcement.

## Ciclo de correção

```text
Ataque
  ↓
Observação
  ↓
Defesa
  ↓
Evidência
  ↓
PASS / FAIL
       ↓
      FAIL
       ↓
Correção
       ↓
Reexecução
       ↓
PASS
       ↓
Regression Corpus
```

Uma falha relevante deve poder alimentar `Fix → Verify → Learn` e gerar um teste de regressão para impedir que o mesmo bypass reapareça.

## Integração com Readiness

O perfil de risco do projeto define quais cenários são obrigatórios. O resultado do Lab deve ser considerado pelo `ReadinessGate` antes da entrega em modos protegidos.

Exemplo:

```text
IMPLEMENTATION
 → TESTS
 → QA
 → SECURITY
      ↳ Security Test Lab
 → ARCHITECTURE
 → REGRESSION
 → RELEASE
 → READY / BLOCKED
```

Um projeto não deve ser marcado como `READY` quando um teste de segurança obrigatório falhar, salvo decisão explícita e auditável de Policy/Release.

## Regression Corpus

Cada vulnerabilidade corrigida deve, quando possível, virar um cenário permanente:

`vulnerability → reproducer → expected defense → regression test`

O corpus deve crescer com o uso do Braim e funcionar como memória operacional de segurança.

## Princípios

1. Deny-by-default.
2. Sem secrets reais.
3. Sem ataque externo implícito.
4. Execução adversarial somente em ambiente controlado.
5. Toda tentativa relevante gera evidência.
6. Falha de defesa é falha de produto, não sucesso do ataque.
7. Correções devem gerar regressão automatizada.
8. O Lab nunca vira autoridade paralela ao PolicyBroker.
