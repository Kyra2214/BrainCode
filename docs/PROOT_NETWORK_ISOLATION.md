# Item 3 — Bloqueio de rede no proot

## Resultado

`proot` não cria namespace de rede e não intercepta sockets. Portanto, não é correto afirmar que uma flag do proot bloqueia a rede dentro do guest.

O caminho Android propaga `ExecutionAuthorization.networkAllowed` até `ManagedSandboxRuntime` e `SandboxProcessLauncher`. O `ProotProcessLauncher` aplica hoje a seguinte política (comportamento **best-effort**, conforme o código e `ProotProcessLauncherNetworkTest`):

- `networkAllowed = true`: inicia o proot normalmente, sem `unshare`;
- `networkAllowed = false` com user namespaces disponíveis e `unshare` executável: envolve o processo com `unshare -n --`;
- `networkAllowed = false` **sem** user namespaces (modo de compatibilidade) **ou sem** `unshare` executável: o processo inicia **sem isolamento de rede** e com a rede aberta. Não há erro nem evento de auditoria; a restrição de rede não é aplicada nesse caso.

A política de rede é decidida por capacidade, a partir da autorização, e não por texto de comando.

## Limitação Android

`unshare(CLONE_NEWNET)` pode ser bloqueado por kernels Android de fabricante ou pelo sandbox do próprio app (o binário costuma existir, mas falha com "Operation not permitted"). O launcher detecta isso via `NamespaceSupport` e nem tenta, para não derrubar a cadeia Brain → Policy → Sandbox. Sem suporte do kernel/root não existe equivalente: nesse caso `networkAllowed=false` **não é garantido**. Isto é uma mitigação condicional, não uma promessa de isolamento universal, e a UI/documentação não devem descrevê-lo como bloqueio de rede.

A próxima evolução possível é um helper nativo assinado, instalado pelo próprio APK, que faça a tentativa de namespace e reporte claramente a falha. Mesmo esse helper não pode superar um kernel que não permita a operação.

## Decisão adotada (a implementar)

Regra: **falhar fechado quando a policy exigir isolamento; best-effort auditado nos demais casos.** Recusar todo comando com `networkAllowed=false` em modo de compatibilidade inutilizaria o app na maioria dos aparelhos, porque quase todo chamador real usa `false`.

1. O launcher passa a informar se o isolamento de rede é aplicável (user namespaces disponíveis e `unshare` encontrado).
2. Capacidades que executam código não confiável (hooks de Skill, plugins, código gerado) exigem isolamento e são recusadas, com motivo explícito, quando ele não existe.
3. As demais capacidades (por exemplo git status, detecção de toolchain, testes) continuam rodando, mas registram um evento de auditoria "isolamento de rede indisponível" e nunca são reportadas como isoladas.
4. Ponto a esclarecer antes de implementar: `AgentSandboxSession.rodarComandoInterno` recusa quando `networkAllowed=true` em modo de compatibilidade, o que parece o inverso do caso que precisa de proteção (`false` sem isolamento disponível).

## Estado

Launcher, runtime e sessão do Agent implementados com o comportamento best-effort descrito acima; a regra da seção "Decisão adotada" ainda **não está implementada**. A validação Gradle deve ser executada em CI com JDK 17 e Android SDK; o ambiente de edição atual não possui esses componentes.
