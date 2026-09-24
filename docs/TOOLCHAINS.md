# Toolchains do Sandbox Mobile

## Escopo

`ToolchainProfile` descreve uma toolchain de forma declarativa: executável de detecção, argumentos de versão, pacotes permitidos e argumentos de validação. `ToolchainDetector` executa apenas a lista de argumentos definida pelo perfil e retorna um diagnóstico estruturado. Ele não aceita uma string de shell arbitrária.

`ToolchainDetector.planInstall` gera um plano explícito com `bash -c`. Antes da instalação, o comando verifica se `/var/lib/apt/lists` contém índices; quando o cache está vazio, executa `apt-get update -qq`. Em seguida instala apenas os pacotes allowlisted com `--no-install-recommends --fix-missing`, preservando a saída final para diagnóstico. O plano ainda não é executado automaticamente e não substitui a autorização da Policy nem os limites do Sandbox.

Essa atualização condicional é necessária para rootfs minimalistas ou recém-criados, nos quais o índice APT pode estar ausente. A tela de Toolchains também exibe `ToolchainStatus.error` quando uma detecção ou instalação falha, tornando o diagnóstico persistido visível para o usuário.

`ToolchainManager` executa esse plano somente por chamada explícita, persiste estados `INSTALLING`, `INSTALLED`, `FAILED`, `REMOVING` e `NOT_INSTALLED`, valida o executável após a instalação e remove apenas os pacotes declarados pelo perfil. Falhas ficam persistidas com diagnóstico para retry ou intervenção da Policy.

## Perfis incluídos

A base inicial cobre Java, Python, Node.js, C/C++, Rust e Go. Android possui perfil específico com SDK/NDK e licenças declaradas. O host de build foi validado com JDK 17, SDK 34, Build Tools 34.0.0, platform-tools e NDK 26.3.11579264; isso não substitui o teste em device.

## Segurança

Os IDs de perfis e pacotes aceitam somente caracteres de catálogo. O detector não concatena entrada de usuário no comando de detecção. A instalação deve ser realizada por uma camada superior que confira readiness do Sandbox, autorização, conectividade e disponibilidade de armazenamento antes de executar o plano.

## Espaço de instalação

Cada `ToolchainStatus` também registra `installedBytes`, calculado com o campo `Installed-Size` dos pacotes declarados no perfil e convertido para bytes. A métrica é exibida separadamente para Android SDK/NDK, Java, Python, Node.js, C/C++, Rust e Go.

`installedBytes` representa o espaço alocado pelos pacotes daquela ferramenta; não representa espaço livre geral do disco. Estados persistidos antigos sem essa informação permanecem com zero até uma atualização do status. A métrica é informativa e não substitui a checagem de armazenamento disponível antes de uma instalação.

## Validação

Foram adicionados testes para detecção bem-sucedida, diagnóstico de ausência, geração de plano com pacotes declarados e rejeição de metacaracteres em pacotes. No host configurado, `:brain:test`, `:android-module:test`, `:app:test` e `:app:assembleDebug` passaram em conjunto com 134 testes Python. Quando o SDK não estiver disponível em outro host, a etapa deve permanecer marcada como parcial no roadmap.
