# Constrói o rootfs "completo" (Ubuntu 24.04) para rodar dentro do app via
# proot. Troca da base anterior (Alpine) para compatibilidade real com
# wheels do pip (manylinux/glibc) e binários pré-compilados do npm — ver
# docs/roadmap-sandbox-fase0.md, decisão registrada em 0.1.
#
# Não usa nada proprietário — só pacotes públicos dos repositórios oficiais
# do Ubuntu e do NodeSource (script oficial, mesma fonte que o setup do
# Node.js recomenda em produção).
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# Ferramentas essenciais para o sandbox de execução de código.
# Node.js 20 LTS vem do repositório oficial NodeSource para compatibilidade
# com Vite, Vitest, Playwright e SDKs modernos usados por agentes.
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl gnupg && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get update && apt-get install -y --no-install-recommends \
    bash \
    coreutils \
    git \
    curl \
    wget \
    ca-certificates \
    gnupg \
    unzip \
    zip \
    jq \
    nano \
    vim \
    openssh-client \
    sqlite3 \
    libsqlite3-dev \
    tzdata \
    locales \
    build-essential \
    pkg-config \
    nodejs \
    python3 \
    python3-pip \
    python3-venv \
    python3-dev \
    python3-pytest \
    bandit \
    pipx \
    default-jdk \
    gradle \
    cmake \
    ninja-build \
    rustc \
    cargo \
    golang \
    ripgrep \
    fd-find \
    tree \
    less \
    file \
    rsync \
    patch \
    diffutils \
    sed \
    gawk \
    procps \
    psmisc \
    htop \
    lsof \
    iproute2 \
    shellcheck \
    universal-ctags \
    gh \
    git-lfs \
    tmux \
    fzf \
    entr \
    net-tools \
    dnsutils \
    netcat-openbsd \
    socat \
    yq \
    pandoc \
    imagemagick \
    graphviz \
    httpie \
    bat \
    p7zip-full \
    xz-utils \
    bzip2 \
    zstd \
    postgresql-client \
    default-mysql-client \
    redis-tools \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*.deb

# Ferramentas Python instaladas globalmente no RootFS. O pipx continua
# disponível para o agente instalar CLIs isoladas; usar pip aqui evita o
# bootstrap lento do ensurepip dentro da emulação arm64 durante a construção.
RUN python3 -m pip install --break-system-packages --no-cache-dir ruff black mypy \
    && rm -rf /root/.cache/pip

# Alguns pacotes Debian podem deixar um .dist-info inválido (por exemplo,
# com `name` minúsculo), fazendo o pip emitir warnings em qualquer comando.
# Remove somente metadata inválida e reinstala o pacote com metadata padrão.
RUN find /usr/lib /usr/local/lib -type d -name 'charset_normalizer-*.dist-info' -print0 2>/dev/null \
    | while IFS= read -r -d '' metadata; do \
        if ! grep -q '^Name:' "$metadata/METADATA" 2>/dev/null; then rm -rf "$metadata"; fi; \
      done \
    && python3 -m pip install --break-system-packages --no-cache-dir --force-reinstall charset-normalizer \
    && rm -rf /root/.cache/pip

# Ferramentas JavaScript disponíveis para qualquer agente sem instalação
# adicional no meio da tarefa.
RUN npm install --global --no-audit --no-fund eslint prettier yarn pnpm \
    && npm cache clean --force \
    && rm -rf /root/.npm

# No Ubuntu, o executável do pacote bat chama-se batcat. Expor também o nome
# esperado pelos agentes e pela documentação multiplataforma.
RUN ln -sf /usr/bin/batcat /usr/local/bin/bat

# Ollama fica fora do RootFS base por ser opcional e muito grande. O plugin
# "ollama" do catálogo instala-o sob demanda quando o usuário solicitar.

# Usuário não-root dentro do rootfs (mesmo isolado via proot -0, boa
# prática — algumas ferramentas de linha de comando reclamam ou se
# recusam a rodar como root real/uid 0, ex: alguns instaladores do npm).
RUN useradd -m -s /bin/bash sandbox
WORKDIR /home/sandbox
USER sandbox

CMD ["/bin/bash"]
