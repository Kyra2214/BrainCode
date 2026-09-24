package com.sandbox.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {
    @Test
    fun `nega acesso por padrao`() {
        val decision = NetworkPolicyBroker().decide(NetworkAccessRequest("api", "tcp", 443, "93.184.216.34"))
        assertFalse(decision.allowed)
    }

    @Test
    fun `autoriza somente regra correspondente`() {
        val policy = NetworkPolicy(rules = setOf(NetworkRule("api", "tcp", 443, setOf("93.184.216.34"))))
        val broker = NetworkPolicyBroker(policy)

        assertTrue(broker.decide(NetworkAccessRequest("api", "tcp", 443, "93.184.216.34")).allowed)
        assertFalse(broker.decide(NetworkAccessRequest("api", "tcp", 80, "93.184.216.34")).allowed)
        assertFalse(broker.decide(NetworkAccessRequest("other", "tcp", 443, "93.184.216.34")).allowed)
    }

    @Test
    fun `regra sem hosts permite host publico na porta declarada`() {
        val policy = NetworkPolicy(rules = setOf(NetworkRule("web", "tcp", 443)))
        assertTrue(NetworkPolicyBroker(policy).decide(NetworkAccessRequest("web", "tcp", 443, "93.184.216.34")).allowed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nao aceita loopback`() {
        NetworkRule("api", "tcp", 443, setOf("127.0.0.1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nao aceita ipv4 mapeado em ipv6`() {
        NetworkRule("api", "tcp", 443, setOf("::ffff:127.0.0.1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nao aceita link local ipv6`() {
        NetworkRule("api", "tcp", 443, setOf("fe80::1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nao aceita porta fora do intervalo`() {
        NetworkRule("api", "tcp", 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nao aceita protocolo arbitrario`() {
        NetworkRule("api", "icmp", 443)
    }
}
