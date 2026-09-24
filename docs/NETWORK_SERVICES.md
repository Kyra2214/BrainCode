# Rede e serviços do Sandbox

## Política

`NetworkPolicy` é deny-by-default. Um acesso só é permitido quando existe uma `NetworkRule` correspondente ao serviço, protocolo, porta e, quando configurado, host. Protocolos aceitos são TCP e UDP, portas ficam limitadas ao intervalo 1–65535 e destinos locais, loopback, link-local e site-local são rejeitados.

`NetworkPolicyBroker` apenas avalia pedidos. Ele não resolve DNS de forma operacional, não abre sockets e não altera firewall. A execução do serviço continua pertencendo ao runtime do Sandbox e deve ser autorizada pela Policy superior antes de iniciar.

## Serviços

A camada agora integra o `ServiceManager`: serviços com porta exigem um `NetworkAccessRequest` correspondente e uma regra autorizadora antes de iniciar ou reiniciar. Serviços sem porta continuam sem necessidade de request. `SandboxPlatform` injeta o `NetworkPolicy` configurado no broker.

## Limitações atuais

A implementação fornece o contrato, a validação e a decisão segura, mas ainda não configura namespaces, firewall, proxy ou cgroups de rede. Essas funções dependem do ambiente de implantação e não devem ser simuladas como se já estivessem disponíveis.

## Testes

Os testes cobrem deny-by-default, autorização por regra, escopo por serviço/porta, regra sem hosts e rejeição de loopback, portas inválidas e protocolos desconhecidos. O módulo Android foi validado com JDK 17 e Android SDK 34; a execução em device/emulador continua sendo um gate separado.
