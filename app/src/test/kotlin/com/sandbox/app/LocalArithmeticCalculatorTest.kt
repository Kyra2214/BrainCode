package com.sandbox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalArithmeticCalculatorTest {
    @Test
    fun `calcula o canario 7 vezes 5 sem rede`() {
        assertEquals("35", LocalArithmeticCalculator.calculate("7×5"))
    }

    @Test
    fun `calcula operadores basicos e percentagem`() {
        assertEquals("20", LocalArithmeticCalculator.calculate("12+8"))
        assertEquals("25", LocalArithmeticCalculator.calculate("100/4"))
        assertEquals("18", LocalArithmeticCalculator.calculate("25-7"))
        assertEquals("5", LocalArithmeticCalculator.calculate("10÷2"))
        assertEquals("120", LocalArithmeticCalculator.calculate("Quanto é 15% de 800?"))
    }

    @Test
    fun `nao interpreta texto arbitrario como expressao`() {
        assertNull(LocalArithmeticCalculator.calculate("qual a temperatura hoje"))
    }
}
