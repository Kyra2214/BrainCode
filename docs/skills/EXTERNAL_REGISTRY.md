# Braim External Skill Registry

O Braim não incorpora o dataset gigante do GitSkills no APK nem no repositório principal. Mantém um catálogo próprio pequeno e consulta fontes externas quando o Secretário precisa descobrir uma capacidade.

## Fontes

- **Anthropic Skills:** `anthropics/skills`, referência para o formato Agent Skills e fonte de Skills compatíveis a serem avaliadas.
- **GitSkills / Hugging Face:** `mvaccargiu/gitskills`, índice externo de descoberta. Uma Skill encontrada não é presumida confiável ou redistribuível.

## Fluxo

```text
Pedido -> Secretário -> busca metadata-first -> catálogo local
      -> Anthropic Skills -> GitSkills/Hugging Face -> candidatos
      -> licença + proveniência + hash + segurança -> PolicyBroker
      -> carregar Skill escolhida -> executar conforme política
```

## Regras

- Catálogo não concede autorização.
- Skills externas começam como `unverified` ou `review_required`.
- Conteúdo completo só é carregado depois da seleção por metadata.
- Origem, caminho, hash, licença e timestamps são preservados.
- A licença do dataset não substitui a licença do repositório de origem.
- Skills não podem alterar as políticas do Braim.
- Efeitos colaterais passam pelo PolicyBroker e, quando aplicável, pelo Sandbox.

## Release

O release contém adaptadores/configuração das fontes, não os ~41 GB do dataset completo. O índice externo pode ser atualizado independentemente do ciclo do APK.
