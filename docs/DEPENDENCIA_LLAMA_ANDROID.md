# Decisão de dependência — llama-android

## Dependência

`dev.ffmpegkit-maintained:llama-android:0.1.1`

Uso atual: AAR pré-compilado para inferência GGUF local via JNI/llama.cpp.

## Decisão

A versão fica **exatamente pinada em `0.1.1`**. Não usar `+`, `latest` ou ranges.

A dependência é aceita como mitigação de curto prazo porque o projeto publica o AAR no Maven Central e também disponibiliza o código-fonte do projeto e a build a partir do fonte.

## Risco aceito

É uma dependência nativa pequena e com um mantenedor principal. Como o AAR é binário, o review normal do código do BrainCode não inspeciona diretamente todo o código nativo empacotado.

Mitigações adotadas:

- versão exata pinada;
- revisão formal registrada neste ADR;
- verificação de integridade do artefato no CI;
- reavaliação se houver CVE relevante, mudança de cadeia de distribuição ou ausência prolongada de manutenção.

## Integridade

O repositório mantém/valida Gradle dependency verification para impedir troca silenciosa do artefato resolvido. A atualização da dependência deve regenerar os metadados de verificação de forma explícita e passar pelo CI.

## Gatilhos de reavaliação

- CVE ou incidente de segurança relevante;
- mudança de mantenedor/distribuição;
- alteração da ABI ou comportamento nativo necessário ao BrainCode;
- ausência de manutenção por aproximadamente 6 meses;
- necessidade de outra ABI além de `arm64-v8a`.

## Fontes

- Maven Central: `dev.ffmpegkit-maintained:llama-android:0.1.1`.
- Código-fonte: `ffmpegkit-maintained/llama-android`.