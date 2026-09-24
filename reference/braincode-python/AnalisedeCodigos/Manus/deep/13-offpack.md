# Auditoria aprofundada — Assemou007/OFFPack

**Snapshot:** `9e92ba8168bb512ab2076561cfa5555bf7645f16` · **Arquivos:** 16 · **Fonte:** https://github.com/Assemou007/OFFPack

## Conclusão
OFFPack é uma implementação real em Go de cache offline para npm, com aproximadamente 1.500 linhas e somente biblioteca padrão. O clone direto corrige a incerteza do levantamento anterior: o repositório existe e declara sete comandos, mas também documenta limitações relevantes.

## Evidência arquivo a arquivo
`main.go` despacha `cache`, `install`, `fetch-popular`, `get`, `update`, `self-install` e `help`. `registry.go` acessa `registry.npmjs.org`; `cache.go` armazena tarballs e manifests em `~/.offpack/<package>/<version>/`; `resolver.go` resolve semver; `extract.go` extrai `.tgz`; `install.go` monta `node_modules`, inclusive dependências aninhadas; `get.go` atualiza `package.json`; `popular.go` contém lista hardcoded; `update.go` busca versões novas; `packagejson.go` lê/escreve manifestos; `selfinstall.go` instala por OS. `AGENTS.md` confirma ausência de testes, linter e version subcommand.

## Fluxo e riscos técnicos
`cache` lê dependências diretas e recursivamente transitivas. `install` não usa rede e falha se faltar qualquer pacote. `get` baixa e grava versão com caret. `update` consulta registry para cada item. O resolver não cobre todos os ranges (`*`, bare `1`, aliases `npm:` e prefixo `v`); `parsePackageSpec` pode quebrar scoped package com range; `sanitizeName` exige round-trip correto; `cacheSeen` é global por comando; o cache é global por usuário; reescrever JSON altera formatação; não há testes.

## Absorção no Brain
Não copiar agora. Usar como referência para um `DependencyCache` pinado, com lockfile completo, manifest de hash SHA-256, SBOM, verificação de integridade, cache por projeto e modo offline explícito. Antes de uso em produção, adicionar testes de semver, scoped packages, ciclos, concorrência, path traversal e pacote malicioso.

## Licença
MIT. A licença permite adaptação, mas não elimina riscos de supply chain dos pacotes cacheados.

## Referências
[1]: https://github.com/Assemou007/OFFPack "OFFPack"
[2]: https://github.com/Assemou007/OFFPack/blob/main/AGENTS.md "OFFPack engineering notes"
