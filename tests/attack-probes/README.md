# Attack probes controlados

Estes probes são fixtures de validação, não payloads de ataque. Cada um exige `PROBE_ALLOW=1`, aplica limites locais e escreve somente em um diretório temporário. O objetivo é verificar que a superfície de execução bloqueia ou encerra corretamente comportamentos perigosos.

## Probes

- `fork-bomb-controlled.sh`: cria no máximo um número configurável de filhos e encerra todos com timeout.
- `bash-c-mount-dd.sh`: tenta `dd` e `mount` dentro de `bash -c`; o resultado esperado é bloqueio/erro, nunca sucesso privilegiado.
- `dev-tcp-controlled.sh`: tenta abrir `/dev/tcp` para um destino de documentação; o resultado esperado é bloqueio quando a rede está proibida.
- `dns-rebinding-fixture.sh`: não consulta a rede; alterna respostas fixture para provar que o chamador revalida o destino depois da resolução.
- `eventstore-truncation-fixture.sh`: trunca uma cópia de um EventStore e verifica que a cadeia é detectada como incompleta.

Execução local de um probe:

```bash
PROBE_ALLOW=1 bash tests/attack-probes/fork-bomb-controlled.sh
```

Nenhum probe substitui a validação ARM64 real do harness. Eles devem ser chamados pelo executor autorizado, não por uma nova superfície de terminal livre.

## Suite completa

`scripts/attack-probes-runner.sh` valida a sintaxe de todos os probes e executa `fork-bomb-controlled`, `bash-c-mount-dd`, `dev-tcp-controlled` e `dns-rebinding-fixture` em sequência (não inclui `eventstore-truncation-fixture.sh`, que se roda à parte):

```bash
PROBE_ALLOW=1 bash scripts/attack-probes-runner.sh
```

Ainda não está ligada ao CI (Marco 3, bypass regression corpus).
