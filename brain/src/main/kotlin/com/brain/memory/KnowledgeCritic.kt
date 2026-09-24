package com.brain.memory

/**
 * Avaliador local do conhecimento recebido de fontes externas.
 *
 * O Critic não ensina nem altera o modelo externo: decide se o candidato
 * já tem evidência suficiente para entrar no recall automático.
 */
interface KnowledgeCritic {
    fun evaluate(entry: KnowledgeEntry): KnowledgeCriticVerdict
}

enum class KnowledgeCriticDecision {
    ACCEPT,
    REJECT,
    UNCERTAIN
}

data class KnowledgeCriticVerdict(
    val decision: KnowledgeCriticDecision,
    val confidence: Double,
    val reason: String
)

/**
 * Gate conservador e determinístico.
 *
 * Regras:
 * - resposta vazia -> rejeita;
 * - sem fonte verificável -> fica pendente;
 * - com fonte e resposta não vazia -> aceita como conhecimento validado
 *   com confiança moderada, sem fingir que houve validação semântica externa.
 *
 * A validação semântica pesada (testes, build ou segunda opinião de API)
 * pode substituir este critic no futuro sem mudar o contrato da memória.
 */
class ConservativeKnowledgeCritic : KnowledgeCritic {
    override fun evaluate(entry: KnowledgeEntry): KnowledgeCriticVerdict {
        if (entry.answer.isBlank()) {
            return KnowledgeCriticVerdict(
                KnowledgeCriticDecision.REJECT,
                0.0,
                "resposta vazia"
            )
        }

        val source = entry.source
        if (source?.uri.isNullOrBlank()) {
            return KnowledgeCriticVerdict(
                KnowledgeCriticDecision.UNCERTAIN,
                0.35,
                "candidato sem fonte verificável"
            )
        }

        return KnowledgeCriticVerdict(
            KnowledgeCriticDecision.ACCEPT,
            0.75,
            "resposta não vazia com fonte registrada"
        )
    }
}
