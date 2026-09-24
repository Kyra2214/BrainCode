# Sandbox — Lifecycle, Shutdown e Observabilidade (Fase 0.5)

> **Escopo:** preparar o Sandbox para ser o runtime seguro e observável sobre o qual, nas fases seguintes, será construído um agente de programação com IA.
>
> **Regra de compatibilidade:** nada deste plano altera o comportamento ou a arquitetura já fechada em 0.1–0.4. As mudanças começam no lifecycle/observabilidade da 0.5.

---

## 1. Objetivo

O Sandbox não deve tratar o encerramento de um comando como um simples `destroy()`.

Para um agente de programação, cada execução precisa deixar evidências suficientes para que uma camada superior consiga responder:

- o que foi executado;
- onde foi executado;
- quando começou e terminou;
- se terminou normalmente, por timeout, cancelamento ou falha;
- qual foi o `exitCode`;
- qual foi o `stdout`;
- qual foi o `stderr`;
- se houve encerramento forçado;
- se o próprio runtime apresentou erro;
- e qual era o estado do Sandbox naquele momento.

O resultado deve continuar sendo útil mesmo depois que o processo proot já morreu.

---

## 2. Princípio central: graceful shutdown + evidência persistida

Fluxo esperado:

```text
IA / aplicação
      |
      v
SandboxRuntime
      |
      +--> execução ativa
      |
      +--> shutdown/cancelamento/timeout
               |
               v
        parar novas execuções
               |
               v
        solicitar encerramento
               |
          +----+----+
          |         |
       terminou   não terminou
          |         |
          |         v
          |       kill forçado
          |         |
          +----+----+
               |
               v
        drenar/coletar streams
               |
               v
        criar ExecutionLog
               |
               v
        persistir resultado
               |
               v
        liberar recursos
```

### Regra importante

A persistência do log acontece **antes da limpeza final dos recursos**, mas nunca deve depender de uma thread de stdout/stderr permanecer viva indefinidamente.

---

## 3. Estados do Sandbox

Adicionar uma máquina de estados explícita para o lifecycle:

```text
NEW
  |
  v
READY <------+
  |           |
  v           |
RUNNING -----+
  |
  +--> STOPPING --> READY
  |
  +--> FAILED
  |
  +--> CLOSED
```

Estados mínimos:

- `NEW` — runtime ainda não iniciado/preparado.
- `READY` — pronto para executar.
- `RUNNING` — existe execução ativa.
- `STOPPING` — encerramento solicitado; nenhuma nova execução deve começar.
- `FAILED` — runtime não conseguiu manter uma execução válida.
- `CLOSED` — recursos liberados e runtime encerrado.

Não permitir execução quando o estado for `STOPPING`, `FAILED` ou `CLOSED`.

---

## 4. Resultado estruturado de execução

O resultado atual de stdout/stderr/exitCode/timedOut deve evoluir sem quebrar os consumidores existentes.

Modelo alvo:

```text
SandboxExecutionResult
├── executionId
├── stdout
├── stderr
├── exitCode
├── timedOut
├── terminationReason
├── forcedKill
├── startedAt
├── finishedAt
└── durationMs
```

### `terminationReason`

Valores iniciais recomendados:

- `PROCESS_EXIT` — processo terminou normalmente.
- `TIMEOUT` — limite de tempo atingido.
- `CANCELLED` — cancelamento solicitado pela aplicação/agente.
- `SHUTDOWN` — runtime encerrado enquanto havia execução.
- `START_FAILED` — processo não conseguiu iniciar.
- `RUNTIME_ERROR` — erro interno do runtime.

`forcedKill=true` deve indicar que o encerramento normal não foi suficiente e foi necessário destruir o processo.

---

## 5. ExecutionLog persistente

Cada execução recebe um `executionId` único e gera um registro persistente.

Modelo lógico:

```text
ExecutionLog
├── executionId
├── sessionId
├── command
├── workingDir
├── startedAt
├── finishedAt
├── durationMs
├── exitCode
├── terminationReason
├── timedOut
├── forcedKill
├── stdout
├── stderr
└── sandboxState
```

### Armazenamento

Na Fase 0.5, usar armazenamento local do app, preferencialmente SQLite por já ser adequado para consultas posteriores do agente.

Os logs não devem ser colocados dentro do rootfs. O rootfs é o ambiente de trabalho; os registros pertencem ao host/app.

---

## 6. SessionLog

Uma sessão agrupa múltiplas execuções do agente:

```text
Session
  |
  +-- exec-001
  +-- exec-002
  +-- exec-003
  +-- exec-004
```

Campos mínimos:

```text
Session
├── sessionId
├── createdAt
├── lastActivityAt
├── status
└── executionCount
```

Isso permitirá, nas fases de IA, reconstruir a sequência que levou a um erro sem depender apenas do último comando.

---

## 7. RuntimeLog separado do ExecutionLog

Erros do Sandbox não devem ser confundidos com erros do comando executado.

Exemplos de eventos de runtime:

- `ROOTFS_NOT_FOUND`
- `PROOT_NOT_FOUND`
- `PROOT_START_FAILED`
- `ROOTFS_EXTRACTION_FAILED`
- `STREAM_READ_ERROR`
- `PROCESS_TIMEOUT`
- `PROCESS_CANCELLED`
- `PROCESS_FORCED_KILL`
- `RUNTIME_CLOSE`
- `RUNTIME_RESET`
- `PERSISTENCE_ERROR`

O `stderr` de `python`, `npm`, `git` etc. continua pertencendo à execução e não ao RuntimeLog.

---

## 8. Shutdown em duas etapas

### Etapa A — encerramento gracioso

1. Marcar o runtime como `STOPPING`.
2. Impedir novas execuções.
3. Sinalizar o processo ativo para encerramento.
4. Aguardar uma janela curta de graceful shutdown.
5. Drenar stdout/stderr disponíveis.
6. Capturar exit code/estado final.

### Etapa B — encerramento forçado

Se o processo não terminar dentro da janela:

1. `destroyForcibly()` no processo proot.
2. Aguardar as threads leitoras por limite definido.
3. Capturar tudo que estiver disponível.
4. Marcar `forcedKill=true`.
5. Registrar o motivo real.
6. Persistir o ExecutionLog.
7. Fechar os streams.
8. Liberar referências e retornar o runtime para estado seguro.

O encerramento forçado é uma condição registrada, não um erro silencioso.

---

## 9. Fechamento do app durante execução

Se o processo Android for encerrado enquanto o proot estiver executando:

- o processo proot não pode ficar deliberadamente abandonado;
- `--kill-on-exit` continua sendo parte do comando proot já existente;
- o lifecycle Android deve cancelar a execução e liberar os recursos quando receber oportunidade;
- o registro deve identificar que a execução foi interrompida por shutdown/cancelamento quando isso puder ser observado;
- na próxima abertura, o Sandbox deve verificar resíduos temporários e estado persistido antes de aceitar nova execução.

Não tentar reconstruir uma execução perdida como se ela tivesse terminado normalmente.

---

## 10. Timeout

O timeout por comando continua existindo, mas passa a ser tratado como uma execução encerrada de forma conhecida.

Ao atingir o limite:

```text
RUNNING
   |
   v
TIMEOUT DETECTED
   |
   v
STOPPING
   |
   +--> encerra proot
   |
   +--> coleta stdout/stderr
   |
   +--> ExecutionLog(timedOut=true)
   |
   v
READY
```

O timeout não deve apagar a saída produzida antes do encerramento.

---

## 11. Captura de stdout/stderr

As threads de leitura devem continuar tolerantes ao fechamento dos streams, como já foi corrigido no runtime atual.

Regras:

- exceção causada por stream fechado durante shutdown não deve derrubar o app;
- stdout e stderr devem ser coletados independentemente;
- o shutdown não deve esperar indefinidamente por uma thread leitora;
- qualquer saída disponível antes do encerramento deve ser preservada;
- se a coleta foi incompleta, o log deve indicar essa condição em vez de fingir que está completo.

---

## 12. Limites de log

Como o agente poderá executar builds, testes e ferramentas que geram muito texto, não manter stdout/stderr ilimitados em memória.

Implementar:

- limite de buffer em memória;
- persistência incremental ou spool temporário quando necessário;
- limite máximo por execução;
- indicação de truncamento (`outputTruncated=true`);
- possibilidade futura de guardar saída completa em arquivo separado.

Nunca deixar um `npm install` ou build com milhões de linhas causar OOM no processo Android.

---

## 13. Segurança e privacidade dos logs

Os logs pertencem ao armazenamento privado do app.

Não registrar automaticamente:

- API keys;
- tokens;
- senhas;
- cookies;
- secrets de ambiente;
- credenciais fornecidas por providers.

Como comandos podem conter argumentos sensíveis, a camada de persistência deve prever futuramente uma representação sanitizada do comando.

O log bruto deve ser protegido do acesso externo normal do Android.

---

## 14. Reset do Sandbox

O reset existente (`purgeAll()`) continua sendo o mecanismo de apagar/reextrair o ambiente.

A 0.5 deve garantir que reset durante ou após uma execução:

1. interrompa execução ativa de forma controlada;
2. aguarde/force o encerramento conforme a política de shutdown;
3. persista o resultado da execução interrompida quando possível;
4. remova rootfs e temporários;
5. não apague automaticamente os ExecutionLogs históricos;
6. deixe o runtime em estado `NEW`/equivalente para nova preparação.

Logs históricos e estado do rootfs são responsabilidades diferentes.

---

## 15. Recuperação após fechar e abrir o app

Na inicialização:

```text
APP START
   |
   v
ler estado persistido
   |
   +--> execução marcada RUNNING?
   |          |
   |          +--> marcar como ABORTED/SHUTDOWN_INTERRUPTED
   |
   v
verificar temporários
   |
   v
validar rootfs existente
   |
   v
READY / necessidade de preparação
```

Uma execução que estava `RUNNING` no último estado conhecido não pode aparecer depois como `PROCESS_EXIT` sem evidência.

---

## 16. API futura para a camada de agente

A Fase 0.5 não implementa IA, mas deixa o contrato pronto para ela.

O agente deverá conseguir perguntar:

```text
execute(command)
getExecution(executionId)
getRecentExecutions(sessionId, limit)
getExecutionOutput(executionId)
cancel(executionId)
shutdown()
reset()
```

A IA não deve precisar conhecer detalhes de ProcessBuilder, threads, proot ou arquivos temporários.

---

## 17. Critérios de aceitação da 0.5

A Fase 0.5 só será marcada como concluída quando os testes comprovarem:

- [ ] execução normal gera ExecutionLog completo;
- [ ] stdout e stderr são preservados;
- [ ] exit code é preservado;
- [ ] timeout encerra o processo e gera log com `timedOut=true`;
- [ ] cancelamento encerra o processo e gera log com motivo correto;
- [ ] shutdown durante execução não deixa proot deliberadamente ativo;
- [ ] encerramento forçado é identificado por `forcedKill=true`;
- [ ] fechamento de streams durante kill não derruba o app;
- [ ] fechamento/reabertura do app recupera estado sem marcar execução interrompida como sucesso;
- [ ] reset não deixa rootfs/temporários corrompidos;
- [ ] logs sobrevivem ao reset do rootfs;
- [ ] saída excessiva não causa crescimento ilimitado de memória;
- [ ] secrets não são persistidos em claro nos logs;
- [ ] testes instrumentados/E2E cobrem os cenários de lifecycle.

---

## 18. Ordem de implementação

1. Criar modelos de estado/termination reason.
2. Criar `ExecutionLog` e `SessionLog` persistentes.
3. Refatorar execução para produzir `executionId` sem quebrar a API atual.
4. Implementar controlador de lifecycle (`RUNNING`/`STOPPING`/`CLOSED`).
5. Implementar shutdown gracioso + fallback forçado.
6. Integrar timeout ao mesmo caminho de encerramento.
7. Integrar cancelamento explícito.
8. Integrar reset ao lifecycle.
9. Adicionar limites de stdout/stderr.
10. Implementar recuperação na abertura do app.
11. Adicionar testes unitários.
12. Adicionar testes instrumentados/E2E e evidências.
13. Só então marcar 0.5 como concluída.

---

## 19. O que NÃO entra na 0.5

Para preservar o escopo:

- nenhum provider de IA;
- nenhum loop de agente;
- nenhum MCP;
- nenhum chat;
- nenhuma alteração no rootfs Ubuntu já validado;
- nenhuma alteração no fluxo funcional fechado de 0.1–0.4 sem necessidade técnica direta para lifecycle.

A 0.5 prepara o terreno. A IA entra somente depois que o runtime for observável, recuperável e previsível.
