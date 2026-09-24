# Item 5 — Assinatura do RootFS

## Implementação

Os scripts de build do RootFS agora produzem, além do arquivo `.tar.gz` e do SHA-256, uma assinatura detached obrigatória para novas releases. O mecanismo é selecionado por `ROOTFS_SIGNING_TOOL`:

- `minisign`, usando `MINISIGN_SECRET_KEY`;
- `cosign`, usando `COSIGN_KEY`.

A assinatura é gerada por `rootfs-builder/sign-rootfs.sh` e validada por `rootfs-builder/verify-rootfs.sh`, que exigem a ferramenta e a chave correspondente. O build principal, o Agent Android e o Agent Extra publicam o sidecar de assinatura e registram no manifesto `signatureUrl`, `signatureAlgorithm` e `signatureRequired: true`.

Exemplo:

```bash
export ROOTFS_SIGNING_TOOL=minisign
export MINISIGN_SECRET_KEY=/secure/keys/rootfs.key
./rootfs-builder/build.sh

export MINISIGN_PUBLIC_KEY='RW...'
./rootfs-builder/verify-rootfs.sh \
  output/rootfs-ubuntu-0.3.3.tar.gz \
  output/rootfs-ubuntu-0.3.3.tar.gz.sig
```

Para Cosign:

```bash
export ROOTFS_SIGNING_TOOL=cosign
export COSIGN_KEY=/secure/keys/rootfs-cosign.key
./rootfs-builder/build.sh

export COSIGN_PUBLIC_KEY=/secure/keys/rootfs-cosign.pub
./rootfs-builder/verify-rootfs.sh \
  output/rootfs-ubuntu-0.3.3.tar.gz \
  output/rootfs-ubuntu-0.3.3.tar.gz.sig
```

## Verificação no Android

`RootfsManifest` passou a transportar os metadados de assinatura. `SandboxResourceManager` continua verificando primeiro o SHA-256 e, para manifests que exigem assinatura, exige um `RootfsSignatureVerifier` configurado. O projeto inclui `Ed25519RootfsSignatureVerifier` como implementação para assinaturas detached Ed25519 codificadas em Base64 e chaves públicas X.509.

O comportamento é fail-closed: a ausência de `signatureRequired` agora equivale a `true`, portanto nenhum manifesto novo é aceito sem um verificador configurado e sem assinatura Ed25519 válida. Artefatos legados precisam ser reemitidos com assinatura antes de serem aceitos por esta versão do aplicativo.

## Limites

A chave privada nunca deve entrar no repositório, no Dockerfile ou no APK. A chave pública deve ser fixada/configurada pela camada de release do app antes de distribuir um manifesto assinado. As releases antigas migradas byte-a-byte não são reconstruídas nem resignadas automaticamente.

## Validação

Foram validados a sintaxe Bash dos cinco scripts de build/verificação e a integração dos novos campos no contrato Kotlin. A suíte Gradle foi iniciada; o resultado final deve ser registrado no commit desta etapa.

## Estado atual dos três manifestos distribuídos (2026-09-24)

Apesar do mecanismo acima existir e do comportamento fail-closed (ausência de
`signatureRequired` equivale a `true`), **os três manifestos hoje empacotados no app não
usam assinatura**:

- `app/src/main/res/raw/rootfs_manifest.json`, `rootfs_android_manifest.json` e
  `rootfs_extra_manifest.json` têm `"signatureRequired": false` explícito.
- `app/src/main/assets/rootfs_trusted_keys.json` está vazio (`{"keys": {}}`), ou seja,
  não há nenhuma chave pública confiável configurada mesmo que um manifesto viesse a
  exigir assinatura.

Isso significa que, na build atual, a verificação de integridade do RootFS depende só do
SHA-256 do manifesto — não há autoridade de confiança criptográfica ativa. Esse é o
estado real, não um bug: o Marco 3 do `ROADMAP_CANONICO` (assinatura Ed25519 dos RootFS
0.3.3/0.4.1/0.5.0 em produção) segue em aberto. Reemitir os três RootFS com assinatura,
publicar a chave pública em `rootfs_trusted_keys.json` e então virar
`signatureRequired: true` nos manifestos é o trabalho pendente desse marco — não faz
parte do escopo da Fase 4 (pós-auditoria).
