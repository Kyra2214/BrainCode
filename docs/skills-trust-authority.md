# Item 6 — Autoridade externa de confiança para Skills

## Implementação

O subsistema `brain_runtime.skills` agora aceita uma `SkillTrustAuthority` injetável. A implementação `StaticSkillTrustAuthority` mantém, fora do manifesto da Skill, uma allowlist de identidade, versão, hash do conteúdo e, opcionalmente, commit, URL e proveniência.

Quando `SkillRegistry` é criado com `require_authority=True`, o registro exige que a autoridade confirme o manifesto antes de aceitar a Skill. A validação do corpo calcula o SHA-256 do arquivo e exige concordância simultânea entre o arquivo, o manifesto e a autoridade externa.

A autoridade nunca deriva aprovação dos próprios metadados da Skill. Um hash ou a flag `verified` declarados apenas pela Skill não são suficientes para satisfazer a política.

## Uso

```python
from brain_runtime.skills import SkillRegistry, StaticSkillTrustAuthority

authority = StaticSkillTrustAuthority({
    ("safe", "1"): {
        "content_hash": "<sha256-do-corpo>",
        "source_commit": "<commit-imutável>",
        "source_url": "https://example.invalid/skill",
        "provenance": ("git", "https://example.invalid/skill", "<commit-imutável>"),
    }
})
registry = SkillRegistry(
    trust_authority=authority,
    require_authority=True,
)
```

A autoridade deve ser carregada por um canal confiável e não pelo conteúdo não confiável da própria Skill. O mecanismo é autocontido no subsistema de Skills e não altera o pipeline de build.

## Cobertura

Foram adicionados testes para registro com autoridade externa, validação de corpo adulterado e rejeição de Skill que apresenta apenas um hash/metadado não aprovado pela autoridade.

A suíte completa passou com **136 testes**, além de `py_compile` e `git diff --check`.

## Estado

Item concluído e publicado em commit separado. Os itens de assinatura do RootFS, tipos Kotlin, rede no proot, mitigação de `/proc` e launcher equivalente ao bwrap permanecem pendentes.

## Limitações deliberadas

`StaticSkillTrustAuthority` é uma implementação de referência baseada em allowlist. A distribuição da allowlist, rotação de chaves e transporte autenticado da autoridade devem ser fornecidos pelo ambiente de release; não são inferidos pelo registry.

---

*Documento referente exclusivamente ao item 6.*
