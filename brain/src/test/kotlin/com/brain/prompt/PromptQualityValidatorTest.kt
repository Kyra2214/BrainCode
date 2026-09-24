package com.brain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptQualityValidatorTest {

    @Test fun `prompt vazio fica abaixo do padrao`() {
        val score = PromptQualityValidator.validar("crie um prompt de X", "", PromptDomain.IMAGEM)
        assertTrue(score.abaixoDoPadrao)
    }

    @Test fun `prompt de imagem estruturado com todos componentes tem score alto mesmo que abaixo do limiar de entrega`() {
        val texto = "Fotografia fotorrealista de um foguete espacial decolando, ambientado em uma base de lançamento à noite. " +
            "Composição: plano geral com o foguete centralizado. Iluminação: iluminação cinematográfica dramática. " +
            "Câmera e lente: câmera DSLR, lente grande angular, profundidade de campo moderada. " +
            "Nível de realismo: fotorrealista. Qualidade: alta definição, detalhes nítidos."
        val score = PromptQualityValidator.validar("crie um prompt fotorrealista de um foguete decolando", texto, PromptDomain.IMAGEM)
        // Regra do produto: só entrega sem passar por melhoria com 90%+. Um prompt bem
        // estruturado já deve pontuar razoavelmente bem nos critérios individuais, mesmo
        // que o total fique abaixo do limiar (aí o executor tenta melhorar antes de entregar).
        assertTrue("presença de elementos deveria ser alta, foi ${score.criterios["presença de elementos"]}", score.criterios.getValue("presença de elementos") >= 0.6)
        assertTrue("estrutura deveria ser alta, foi ${score.criterios["estrutura"]}", score.criterios.getValue("estrutura") >= 0.6)
    }

    @Test fun `termos vagos reduzem especificidade`() {
        val vago = PromptQualityValidator.validar("crie um prompt de uma imagem", "uma imagem bonita e legal de algo interessante", PromptDomain.IMAGEM)
        val especifico = PromptQualityValidator.validar(
            "crie um prompt de uma imagem",
            "Fotografia de uma montanha nevada ao amanhecer, com névoa baixa no vale e luz dourada lateral.",
            PromptDomain.IMAGEM
        )
        assertTrue(vago.criterios.getValue("especificidade") < especifico.criterios.getValue("especificidade"))
    }

    @Test fun `termos contraditorios reduzem coerencia`() {
        val score = PromptQualityValidator.validar(
            "crie um prompt",
            "Uma cena durante o dia, mas também à noite, em estilo realista e também cartoon.",
            PromptDomain.IMAGEM
        )
        assertTrue(score.criterios.getValue("coerência") < 1.0)
    }

    @Test fun `requisito composto de ceu estrelado e obrigatorio`() {
        val pedido = "crie um prompt de um foguete com céu estrelado ao fundo"
        val semCeu = PromptQualityValidator.validar(pedido, "Fotografia de um foguete, ambientado em fundo genérico. Composição: equilibrada.", PromptDomain.IMAGEM)
        val comCeu = PromptQualityValidator.validar(pedido, "Fotografia de um foguete, com céu estrelado ao fundo. Composição: equilibrada.", PromptDomain.IMAGEM)

        assertEquals(0.0, semCeu.criterios.getValue("presença de elementos"), 0.0001)
        assertTrue(comCeu.criterios.getValue("presença de elementos") > 0.0)
    }

    @Test fun `regra de produto - limiar minimo de entrega e 90 por cento`() {
        assertEquals(0.90, PromptQualityScore.LIMIAR_MINIMO, 0.0001)
    }

    // Os critérios de especificidade/coerência não podiam avaliar corretamente um
    // pedido/prompt em inglês — as listas eram só em português.
    @Test fun `termos vagos em ingles tambem reduzem especificidade`() {
        val vago = PromptQualityValidator.validar("create a prompt of an image", "a nice and cool picture of something interesting", PromptDomain.IMAGEM)
        val especifico = PromptQualityValidator.validar(
            "create a prompt of an image",
            "Photograph of a snow-capped mountain at dawn, with low fog in the valley and golden side light.",
            PromptDomain.IMAGEM
        )
        assertTrue(vago.criterios.getValue("especificidade") < especifico.criterios.getValue("especificidade"))
    }

    @Test fun `termos contraditorios em ingles tambem reduzem coerencia`() {
        val score = PromptQualityValidator.validar(
            "create a prompt",
            "A scene during the day, but also at night, in realistic style and also cartoon.",
            PromptDomain.IMAGEM
        )
        assertTrue(score.criterios.getValue("coerência") < 1.0)
    }
}
