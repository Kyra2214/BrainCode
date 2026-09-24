# Isolamento real e limites de segurança

O BrainCode usa `proot` para compatibilidade de filesystem e execução de um rootfs sem privilégios. **proot não é equivalente a um jail de kernel forte**: ele não cria namespace de PID, namespace de mount, namespace de usuário, cgroup dedicado ou firewall por processo.

As garantias implementadas são mais estreitas: validação de capacidades, política de rede antes da execução, `unshare -n` quando disponível, limites `setrlimit` verificados, watchdog de processos por árvore, encerramento da árvore via grupo ou `/proc`, allowlists de comandos/capacidades e auditoria de eventos.

Quando `unshare` não existe ou o kernel não oferece user namespaces, uma execução com rede proibida **continua sem isolamento de rede** (best-effort; ver `docs/PROOT_NETWORK_ISOLATION.md`, que registra a decisão de falhar fechado só quando a policy exigir isolamento). Quando o ambiente não fornece namespaces reais, a documentação e a UI não devem prometer isolamento de kernel, contenção de UID ou invisibilidade de processos.

A validação em dispositivo/emulador ARM64 continua sendo um gate separado. Testes JVM, inspeções estáticas e probes controlados demonstram contratos locais, mas não substituem a execução real no Android.
