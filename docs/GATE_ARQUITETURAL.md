# Gate arquitetural de superfícies de execução

`scripts/architecture-gate.sh` procura chamadas diretas a `.execute(`, `.launch(` e `ProcessBuilder(` nos módulos Kotlin e Java. O gate falha quando encontra uma chamada fora da allowlist explícita dos chamadores de baixo nível.

A allowlist é deliberadamente pequena e versionada no próprio script. Uma nova superfície deve primeiro ser encaminhada para o componente autorizado e só então receber uma revisão explícita da allowlist. O gate não substitui testes funcionais nem prova que o chamador autorizado aplica a política correta.

Execução:

```bash
bash scripts/architecture-gate.sh
```

O gate é uma validação estática e não exige Gradle, Android SDK ou dispositivo conectado.
