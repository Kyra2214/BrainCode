#!/bin/bash
set -euo pipefail
VERSION="${VERSION:-0.4.1}"
PLATFORM="${PLATFORM:-linux/arm64}"
IMAGE="sandbox-rootfs-agent-extra:${VERSION}"
OUTPUT_DIR="../output"
OUTPUT_FILE="rootfs-agent-extra-${VERSION}.tar.gz"
mkdir -p "$OUTPUT_DIR"
echo "==> Construindo RootFS extra ${VERSION}..."
docker build --network host --platform "$PLATFORM" -f Dockerfile.agent-extra -t "$IMAGE" .
CID=$(docker create --platform "$PLATFORM" "$IMAGE")
trap 'docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT
echo "==> Exportando camada extra..."
docker export "$CID" | gzip -1 > "$OUTPUT_DIR/$OUTPUT_FILE"
docker rm "$CID" >/dev/null
trap - EXIT
sha256sum "$OUTPUT_DIR/$OUTPUT_FILE" > "$OUTPUT_DIR/$OUTPUT_FILE.sha256"
HASH=$(cut -d' ' -f1 "$OUTPUT_DIR/$OUTPUT_FILE.sha256")
SIZE=$(stat -c%s "$OUTPUT_DIR/$OUTPUT_FILE" 2>/dev/null || stat -f%z "$OUTPUT_DIR/$OUTPUT_FILE")
SIGNATURE_FILE="$OUTPUT_DIR/$OUTPUT_FILE.sig"
./sign-rootfs.sh "$OUTPUT_DIR/$OUTPUT_FILE" "$SIGNATURE_FILE"
cat > "$OUTPUT_DIR/agent_extra_manifest.json" <<EOF
{
  "version": "${VERSION}",
  "arch": "arm64-v8a",
  "baseRootfs": "0.3.3",
  "distro": "ubuntu-24.04",
  "url": "https://github.com/Kyra2214/SandBox/releases/download/rootfs-agent-v${VERSION}/${OUTPUT_FILE}",
  "sizeBytes": ${SIZE},
  "sha256": "${HASH}",
  "minAppVersion": "1.0.0",
  "signatureUrl": "https://github.com/Kyra2214/SandBox/releases/download/rootfs-agent-v${VERSION}/${OUTPUT_FILE}.sig",
  "signatureAlgorithm": "${ROOTFS_SIGNING_TOOL}",
  "signatureRequired": true
}
EOF
ls -lh "$OUTPUT_DIR/$OUTPUT_FILE" "$OUTPUT_DIR/$OUTPUT_FILE.sha256" "$SIGNATURE_FILE" "$OUTPUT_DIR/agent_extra_manifest.json"
