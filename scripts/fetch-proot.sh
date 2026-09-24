#!/usr/bin/env bash
#
# fetch-proot.sh — baixa o binário `proot` oficial do repositório Termux
# (open source, GPL-2.0) e posiciona em jniLibs/<abi>/libproot.so.
#
# Por que este script existe e não roda automaticamente no build:
# o ambiente que gerou este projeto não tem acesso de rede (sandbox sem
# egress). Rode este script manualmente, uma vez, na sua máquina ou no CI,
# com os artefatos versionados depois (jniLibs não deveria ser gerado a
# cada build — é um binário estável).
#
# Uso:
#   ./scripts/fetch-proot.sh                # baixa só arm64-v8a (padrão)
#   ./scripts/fetch-proot.sh all             # baixa arm64-v8a + armeabi-v7a
#
# Fonte: repositório principal do Termux (termux-main), pacote `proot`,
# licença GPL-2.0, mesmo binário instalado por `pkg install proot` no
# Termux. Mirror estável usado abaixo: cdimage.debian.org/mirror/termux.dev
# (espelho oficial do projeto Debian para os pacotes do Termux).

set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../android-module" && pwd)"
JNI_DIR="$MODULE_DIR/src/main/jniLibs"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

BASE_URL="https://cdimage.debian.org/mirror/termux.dev/apt/termux-main/pool/main/p/proot"

# abi Android -> nome de arquitetura usado pelo Termux
declare -A ABI_MAP=(
  [arm64-v8a]="aarch64"
  [armeabi-v7a]="arm"
)

TARGETS=("arm64-v8a")
if [[ "${1:-}" == "all" ]]; then
  TARGETS=("arm64-v8a" "armeabi-v7a")
fi

fetch_one() {
  local android_abi="$1"
  local termux_arch="${ABI_MAP[$android_abi]}"

  echo "==> Resolvendo versão atual do pacote proot ($termux_arch)..."
  # A pasta lista todos os .deb publicados; pegamos o mais recente para essa arch.
  local listing deb_name deb_url
  listing="$(curl -fsSL "$BASE_URL/")"
  deb_name="$(echo "$listing" \
    | grep -oE "proot_[^\"]+_${termux_arch}\.deb" \
    | sort -V | tail -n1)"

  if [[ -z "$deb_name" ]]; then
    echo "ERRO: não encontrei um .deb para arch '$termux_arch' em $BASE_URL" >&2
    exit 1
  fi

  deb_url="$BASE_URL/$deb_name"
  echo "==> Baixando $deb_url"
  curl -fsSL -o "$WORK_DIR/$deb_name" "$deb_url"

  echo "==> Extraindo $deb_name"
  local extract_dir="$WORK_DIR/extract-$android_abi"
  mkdir -p "$extract_dir"
  ar x "$WORK_DIR/$deb_name" --output="$extract_dir"

  # o payload pode vir como data.tar.xz, data.tar.zst ou data.tar.gz
  local data_archive
  data_archive="$(find "$extract_dir" -maxdepth 1 -name 'data.tar*' | head -n1)"
  if [[ -z "$data_archive" ]]; then
    echo "ERRO: não achei data.tar.* dentro de $deb_name" >&2
    exit 1
  fi

  local payload_dir="$extract_dir/payload"
  mkdir -p "$payload_dir"
  tar -xf "$data_archive" -C "$payload_dir"

  local proot_bin
  proot_bin="$(find "$payload_dir" -type f -name 'proot' | head -n1)"
  if [[ -z "$proot_bin" ]]; then
    echo "ERRO: binário 'proot' não encontrado dentro do pacote extraído" >&2
    exit 1
  fi

  local dest_dir="$JNI_DIR/$android_abi"
  mkdir -p "$dest_dir"
  cp "$proot_bin" "$dest_dir/libproot.so"
  chmod 755 "$dest_dir/libproot.so"

  # O AGP trata apenas nomes JNI terminados em `.so` como bibliotecas nativas
  # do APK; `libtalloc.so.2` seria descartada do pacote. Reescrevemos apenas
  # esse NEEDED para o nome estável empacotado abaixo.
  command -v patchelf >/dev/null || {
    echo "ERRO: patchelf é necessário para adaptar as dependências do proot" >&2
    exit 1
  }
  patchelf --replace-needed libtalloc.so.2 libtalloc.so "$dest_dir/libproot.so"

  echo "==> OK: $dest_dir/libproot.so  (origem: $deb_name)"
  echo "    sha256: $(sha256sum "$dest_dir/libproot.so" | cut -d' ' -f1)"

  # registra a origem exata para o THIRD_PARTY_NOTICES.md
  echo "$android_abi|$deb_name|$deb_url|$(sha256sum "$dest_dir/libproot.so" | cut -d' ' -f1)" \
    >> "$MODULE_DIR/../docs/proot-binary-provenance.txt"

  # O executável do Termux não é estaticamente ligado: também precisa de
  # libtalloc e libandroid-shmem. Sem elas, o linker do Android falha antes
  # de o proot iniciar ("library ... not found").
  local packages_url="https://cdimage.debian.org/mirror/termux.dev/apt/termux-main/dists/stable/main/binary-${termux_arch}/Packages"
  local packages_index
  packages_index="$(curl -fsSL "$packages_url")"
  local dependency
  for dependency in libtalloc libandroid-shmem; do
    local dependency_record dependency_path dependency_deb dependency_url
    dependency_record="$(printf '%s\n' "$packages_index" | awk -v p="$dependency" 'BEGIN{RS="\n\n"} $0 ~ "^Package: "p"(\\n|$)" {print; exit}')"
    dependency_path="$(printf '%s\n' "$dependency_record" | awk '/^Filename: / {print $2}')"
    dependency_deb="$(basename "$dependency_path")"
    dependency_url="https://cdimage.debian.org/mirror/termux.dev/apt/termux-main/$dependency_path"
    echo "==> Baixando dependência $dependency: $dependency_url"
    curl -fsSL -o "$WORK_DIR/$dependency_deb" "$dependency_url"
    local dependency_dir="$WORK_DIR/extract-$android_abi-$dependency"
    mkdir -p "$dependency_dir"
    ar x "$WORK_DIR/$dependency_deb" --output="$dependency_dir"
    local dependency_data
    dependency_data="$(find "$dependency_dir" -maxdepth 1 -name 'data.tar*' | head -n1)"
    mkdir -p "$dependency_dir/payload"
    tar -xf "$dependency_data" -C "$dependency_dir/payload"
    local dependency_lib
    dependency_lib="$(find "$dependency_dir/payload" -type f -path '*/usr/lib/*' \
      \( -name 'libtalloc.so.*' -o -name 'libandroid-shmem.so' \) | head -n1)"
    if [[ -z "$dependency_lib" ]]; then
      echo "ERRO: biblioteca runtime não encontrada no pacote $dependency_deb" >&2
      exit 1
    fi
    local dependency_name
    dependency_name="$(basename "$dependency_lib")"
    if [[ "$dependency" == "libtalloc" ]]; then
      dependency_name="libtalloc.so"
    fi
    cp "$dependency_lib" "$dest_dir/$dependency_name"
    chmod 755 "$dest_dir/$dependency_name"
    echo "    sha256: $(sha256sum "$dest_dir/$dependency_name" | cut -d' ' -f1)"
    echo "$android_abi|$dependency_deb|$dependency_url|$(sha256sum "$dest_dir/$dependency_name" | cut -d' ' -f1)" \
      >> "$MODULE_DIR/../docs/proot-binary-provenance.txt"
  done
}

for abi in "${TARGETS[@]}"; do
  fetch_one "$abi"
done

echo ""
echo "Pronto. Não esqueça de:"
echo "  1) Conferir docs/proot-binary-provenance.txt (hash + URL de origem)"
echo "  2) Preencher docs/THIRD_PARTY_NOTICES.md com a versão baixada"
echo "  3) Testar em device real: proot --version dentro do sandbox"
