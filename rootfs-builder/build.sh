#!/bin/bash
# Constrói o rootfs completo (Ubuntu 24.04), exporta como tar.gz versionado
# e gera o manifest.json para uma release do BrainCode.
set -euo pipefail

VERSION="${VERSION:-0.3.3}"
PLATFORM="${PLATFORM:-linux/arm64}"

# O Sandbox agora é o subsistema de execução do BrainCode.
GITHUB_REPO="Kyra2214/BrainCode"
RELEASE_TAG="rootfs-v${VERSION}"

IMAGE_NAME="sandbox-rootfs-builder"
OUTPUT_DIR="../output"
OUTPUT_FILE="rootfs-ubuntu-${VERSION}.tar.gz"

mkdir -p "$OUTPUT_DIR"

echo "==> Construindo imagem Docker..."
docker build --network host --platform "$PLATFORM" -t "$IMAGE_NAME" .

echo "==> Criando container temporário..."
CONTAINER_ID=$(docker create --platform "$PLATFORM" "$IMAGE_NAME")
echo "==> Exportando filesystem..."
docker export "$CONTAINER_ID" | gzip > "$OUTPUT_DIR/$OUTPUT_FILE"
echo "==> Limpando container temporário..."
docker rm "$CONTAINER_ID" > /dev/null

echo "==> Calculando SHA-256..."
sha256sum "$OUTPUT_DIR/$OUTPUT_FILE" > "$OUTPUT_DIR/$OUTPUT_FILE.sha256"
SHA256_HASH="$(cut -d' ' -f1 "$OUTPUT_DIR/$OUTPUT_FILE.sha256")"
SIZE_BYTES="$(stat -c%s "$OUTPUT_DIR/$OUTPUT_FILE" 2>/dev/null || stat -f%z "$OUTPUT_DIR/$OUTPUT_FILE")"

echo "==> Assinando RootFS..."
SIGNATURE_FILE="$OUTPUT_DIR/$OUTPUT_FILE.sig"
./sign-rootfs.sh "$OUTPUT_DIR/$OUTPUT_FILE" "$SIGNATURE_FILE"
SIGNATURE_URL="https://github.com/${GITHUB_REPO}/releases/download/${RELEASE_TAG}/${OUTPUT_FILE}.sig"
SIGNATURE_ALGORITHM="${ROOTFS_SIGNING_TOOL}"

DOWNLOAD_URL="https://github.com/${GITHUB_REPO}/releases/download/${RELEASE_TAG}/${OUTPUT_FILE}"

echo "==> Gerando manifest.json..."
cat > "$OUTPUT_DIR/rootfs_manifest.json" << EOF
{
  "version": "${VERSION}",
  "arch": "arm64-v8a",
  "distro": "ubuntu-24.04",
  "url": "${DOWNLOAD_URL}",
  "sizeBytes": ${SIZE_BYTES},
  "sha256": "${SHA256_HASH}",
  "minAppVersion": "1.0.0",
  "signatureUrl": "${SIGNATURE_URL}",
  "signatureAlgorithm": "${SIGNATURE_ALGORITHM}",
  "signatureRequired": true
}
EOF

ls -lh "$OUTPUT_DIR/$OUTPUT_FILE"

echo ""
echo "==> Pronto: $OUTPUT_DIR/$OUTPUT_FILE"
echo "==> Manifesto gerado: $OUTPUT_DIR/rootfs_manifest.json"
echo ""
echo "Para publicar uma NOVA build, use:"
echo "  gh release create ${RELEASE_TAG} \\"
echo "    \"$OUTPUT_DIR/$OUTPUT_FILE\" \"$OUTPUT_DIR/$OUTPUT_FILE.sha256\" \"$SIGNATURE_FILE\" \\"
echo "    --repo ${GITHUB_REPO} \\"
echo "    --title \"Rootfs ${VERSION} (Ubuntu 24.04)\""
echo ""
echo "IMPORTANTE: para os RootFS já validados do SandBox, NÃO use este build."
echo "Use rootfs-builder/migrate-sandbox-releases.sh para migração byte-a-byte."
