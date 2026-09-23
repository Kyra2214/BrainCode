# Gestão de chaves confiáveis

O BrainCode mantém as chaves públicas confiáveis em `app/src/main/assets/braincode/trusted-signing-keys.json`. O asset contém somente chaves públicas Ed25519, um `keyId`, algoritmo, finalidade e estado (`active` ou `revoked`). Chaves privadas nunca entram no repositório, no APK ou nos logs.

A facade carrega somente chaves `active` e fornece o mesmo mapa ao `SkillRegistry` e ao `WorkflowMarketplaceRegistry`. Registro, resolução e pin de manifestos não fazem download, instalação ou habilitação automática. A assinatura valida apenas a proveniência do manifesto; a autorização continua pertencendo ao `PolicyBroker`.

A rotação deve ser feita adicionando uma nova chave pública com novo `keyId`, publicando manifests assinados pela nova chave, e somente depois marcando a chave antiga como `revoked` em uma atualização do aplicativo. Revogação de uma Skill já registrada é persistida em `revoked-skills.tsv`. Remoção física de uma chave exige uma versão posterior do aplicativo e revisão dos manifests ainda suportados.

A chave atualmente presente é `braincode-dev-2026`, destinada exclusivamente a manifests de desenvolvimento. Para produção, ela deve ser substituída por uma chave gerenciada fora do repositório e provisionada no processo de release.
