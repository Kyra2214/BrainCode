package com.sandbox.app

import java.math.BigDecimal
import java.math.RoundingMode

/** Avalia somente expressões aritméticas explícitas; não executa código nem usa rede. */
object LocalArithmeticCalculator {
    private val bareExpression = Regex("^[0-9\\s.,()+\\-*/×÷xX]+$")
    private val percentOf = Regex("(?i)^\\s*([0-9]+(?:[.,][0-9]+)?)\\s*%\\s*(?:de|de)\\s*([0-9]+(?:[.,][0-9]+)?)\\s*[?!.]*\\s*$")
    private val commandPrefix = Regex("(?i)^\\s*(?:calcule|calcular|quanto\\s+é|quanto\\s+e|qual\\s+o\\s+resultado\\s+de)\\s+(.+?)\\s*[?!.]*\\s*$")

    fun calculate(prompt: String): String? {
        val normalized = prompt.trim()
        val expression = commandPrefix.matchEntire(normalized)?.groupValues?.get(1)?.trim() ?: normalized
        val percent = percentOf.matchEntire(expression)
        if (percent != null) {
            val value = percent.groupValues[1].toDecimal() ?: return null
            val base = percent.groupValues[2].toDecimal() ?: return null
            return format(value * base / BigDecimal(100))
        }
        if (!bareExpression.matches(expression) || expression.none { it.isDigit() } || !expression.any { it in "+-*/×÷xX" }) return null
        return runCatching { format(Parser(expression).parse()) }.getOrNull()
    }

    private fun String.toDecimal(): BigDecimal? = replace(',', '.').toBigDecimalOrNull()

    private fun format(value: BigDecimal): String {
        val clean = value.setScale(10, RoundingMode.HALF_UP).stripTrailingZeros()
        return clean.toPlainString().ifBlank { "0" }
    }

    private class Parser(expression: String) {
        private val input = expression.replace('×', '*').replace('÷', '/').replace('x', '*').replace('X', '*').replace(',', '.')
        private var index = 0

        fun parse(): BigDecimal {
            val value = sum()
            skipSpaces()
            require(index == input.length) { "expressão inválida" }
            return value
        }

        private fun sum(): BigDecimal {
            var value = product()
            while (true) {
                skipSpaces()
                value = when {
                    take('+') -> value + product()
                    take('-') -> value - product()
                    else -> return value
                }
            }
        }

        private fun product(): BigDecimal {
            var value = factor()
            while (true) {
                skipSpaces()
                value = when {
                    take('*') -> value * factor()
                    take('/') -> value.divide(factor(), 10, RoundingMode.HALF_UP)
                    else -> return value
                }
            }
        }

        private fun factor(): BigDecimal {
            skipSpaces()
            if (take('+')) return factor()
            if (take('-')) return factor().negate()
            if (take('(')) {
                val value = sum()
                require(take(')')) { "parêntese não fechado" }
                return value
            }
            val start = index
            while (index < input.length && (input[index].isDigit() || input[index] == '.')) index++
            require(index > start) { "número esperado" }
            return input.substring(start, index).toBigDecimal()
        }

        private fun take(expected: Char): Boolean {
            if (index < input.length && input[index] == expected) {
                index++
                return true
            }
            return false
        }

        private fun skipSpaces() {
            while (index < input.length && input[index].isWhitespace()) index++
        }
    }
}
