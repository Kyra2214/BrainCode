# Operações da UI — 2026-09-16

## Git no workspace

A tela de Chat/Threads e o dispatcher operacional expõem os comandos `/git status`, `/git diff`, `/git commit <mensagem>` e `/git push`. As operações `commit` e `push` usam o `GitManager` existente e são sempre limitadas ao projeto selecionado em `/home/sandbox/workspace/projects/<nome>`. O comando de commit executa `git add -A` seguido de `git commit -m <mensagem>`; o push executa `git push` no mesmo diretório. O resultado padrão/erro é registrado como evento de relatório na thread.

Essas operações não transformam texto livre em shell: o parser reconhece apenas as formas operacionais declaradas, exige um workspace selecionado e encaminha somente a mensagem do commit como argumento do `GitManager`. O push continua sujeito às credenciais e políticas do repositório configuradas no workspace.

## Delivery em ZIP

O comando `/deliver` mantém a publicação local do recibo `ObservableDelivery`, que calcula hashes SHA-256 e metadados dos artefatos. Depois do recibo, `BrainIntegrationFacade.packageLocalDelivery()` cria um ZIP em `files/brain/deliveries/<run-id>.zip`, usando caminhos relativos ao workspace e ignorando links simbólicos. A UI informa o nome do arquivo, tamanho e o SHA-256 do ZIP gerado.

O arquivo é local ao aplicativo e não é publicado automaticamente em rede. A disponibilização para download ou compartilhamento deve ser uma ação posterior da UI, com a política de acesso correspondente. O diretório de saída fica fora da raiz empacotada, evitando que o próprio ZIP seja incluído recursivamente.

## Espaço de instalação das ferramentas

O status de cada `ToolchainProfile` inclui `installedBytes`. Esse valor é calculado por pacote com `dpkg-query` usando o campo `Installed-Size`, convertido de KiB para bytes, e persistido junto ao estado/cache da toolchain. A métrica é exibida na tela de Operações para Android SDK/NDK, Java, Python, Node.js, C/C++, Rust e Go.

> **Distinção importante:** `installedBytes` é o espaço de instalação alocado pelos pacotes declarados daquela ferramenta. Não é o espaço livre geral do disco, nem uma estimativa do espaço disponível no dispositivo. Estados antigos sem essa informação são tratados como `0` até uma nova atualização do status.

A métrica não altera a autorização de instalação. Antes de instalar ou remover pacotes, a camada superior ainda deve verificar readiness, policy, conectividade e disponibilidade de armazenamento.

## Commits e validação

- `b9a4143` — ativação de Git commit/push e delivery em ZIP;
- `b16c841` — espaço alocado por toolchain;
- `8188d92` — correção do caminho persistente do arquivo ZIP.

O CI final associado ao commit `8188d92` passou por testes JVM/unitários, assemble do APK debug, lint Android e upload do APK.
