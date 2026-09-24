# Decisão de segurança do terminal

O terminal livre não faz parte da release. A seção de comando e os controles de entrada são renderizados somente quando `BuildConfig.DEBUG` é verdadeiro, mantendo a superfície disponível para desenvolvimento local sem expô-la no APK de produção.

A execução de capacidades de produto deve usar `AuthorizedCapabilityExecutor`, que resolve uma capacidade allowlisted e passa por `PolicyBroker` antes do executor. O bloqueio de `bash -c` e de interpretadores de shell no `SecureCommandExecutor` permanece como defesa adicional.

Esta decisão evita tratar terminal bruto como uma capacidade comum. Se no futuro for necessário habilitá-lo em release, ele deverá ser reestruturado como `terminal.raw`, com aprovação explícita, orçamento, expiração e auditoria antes de qualquer exposição.
