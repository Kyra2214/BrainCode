# Item 10 — Kill da árvore de processos

## Implementação

O `ManagedSandboxRuntime` agora reconhece launchers que iniciam o workload em um process group próprio. O `ProotProcessLauncher` usa `setsid` quando disponível; no encerramento, o runtime envia `TERM` e depois `KILL` ao grupo inteiro, além de manter o fallback por `ProcessHandle.descendants()` para hosts sem as ferramentas POSIX necessárias.

O sandbox Python já iniciava cada job com `start_new_session=True`. O comportamento foi centralizado em `brain_runtime.process_tree.terminate_process_group`, usado nos caminhos de cancelamento, timeout e falha de cgroup.

## Segurança e limites

O encerramento por grupo impede que filhos comuns de um job sobrevivam ao timeout ou cancelamento do líder. Isso não equivale a um namespace de PID: processos que escapem do grupo por mecanismos externos ou que tenham sido reparentados antes do sinal exigem controles adicionais do host, como cgroup ou namespace.

Em Android, a garantia depende da presença e permissão de `setsid`/`kill`. Quando essas ferramentas não estão disponíveis, o runtime mantém o encerramento do processo líder e dos descendentes observáveis, sem afirmar isolamento mais forte do que o host fornece.

## Cobertura

Foi adicionado um teste controlado que cria um filho, força timeout do job e verifica que o filho não consegue produzir um artefato após o encerramento do grupo.
