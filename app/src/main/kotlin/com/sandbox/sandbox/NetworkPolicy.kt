package com.sandbox.sandbox

import java.net.InetAddress

/** Regra explícita para permitir uma combinação serviço/protocolo/porta. */
data class NetworkRule(
    val serviceId: String,
    val protocol: String,
    val port: Int,
    val hosts: Set<String> = emptySet()
) {
    init {
        require(serviceId.matches(Regex("[A-Za-z0-9._-]+"))) { "ID de serviço inválido" }
        require(protocol.lowercase() in setOf("tcp", "udp")) { "Protocolo não suportado" }
        require(port in 1..65535) { "Porta inválida" }
        require(hosts.all { isSafeHost(it) }) { "Host inválido ou reservado" }
    }
}

data class NetworkAccessRequest(
    val serviceId: String,
    val protocol: String,
    val port: Int,
    val host: String
)

data class NetworkDecision(val allowed: Boolean, val reason: String)

data class NetworkPolicy(
    val defaultDeny: Boolean = true,
    val rules: Set<NetworkRule> = emptySet()
)

/** Avalia somente regras previamente declaradas; não resolve DNS nem abre sockets. */
class NetworkPolicyBroker(private val policy: NetworkPolicy = NetworkPolicy()) {
    fun decide(request: NetworkAccessRequest): NetworkDecision {
        val normalized = request.copy(protocol = request.protocol.lowercase(), host = request.host.trim().lowercase())
        require(normalized.serviceId.matches(Regex("[A-Za-z0-9._-]+"))) { "ID de serviço inválido" }
        require(normalized.protocol in setOf("tcp", "udp")) { "Protocolo não suportado" }
        require(normalized.port in 1..65535) { "Porta inválida" }
        require(isSafeHost(normalized.host)) { "Host inválido ou reservado" }
        val match = policy.rules.any { rule ->
            rule.serviceId == normalized.serviceId &&
                rule.protocol.lowercase() == normalized.protocol &&
                rule.port == normalized.port &&
                (rule.hosts.isEmpty() || normalized.host in rule.hosts.map(String::lowercase))
        }
        return when {
            match -> NetworkDecision(true, "regra explícita autorizou o acesso")
            policy.defaultDeny -> NetworkDecision(false, "acesso negado por padrão; nenhuma regra corresponde")
            else -> NetworkDecision(true, "política permissiva sem regra correspondente")
        }
    }
}

internal fun isSafeHost(value: String): Boolean {
    if (value.isBlank() || value.length > 253 || value.contains('/') || value.contains('@')) return false
    val lower = value.lowercase()
    if (lower == "localhost" || lower.endsWith(".localhost")) return false
    val looksLikeHostname = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]*"))
    val looksLikeIpv6 = value.contains(':') && value.matches(Regex("[0-9A-Fa-f:.%]+"))
    if (!looksLikeHostname && !looksLikeIpv6) return false
    val addresses = runCatching { InetAddress.getAllByName(value) }.getOrNull() ?: return false
    if (addresses.isEmpty()) return false
    return addresses.none { it.isForbiddenDestination() }
}

private fun InetAddress.isForbiddenDestination(): Boolean {
    if (isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress || isAnyLocalAddress || isMulticastAddress) return true
    val bytes = address
    // IPv4-mapped IPv6: ::ffff:10.0.0.1, ::ffff:127.0.0.1, etc.
    if (bytes.size == 16 && bytes.copyOfRange(0, 10).all { it == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()) {
        val a = bytes[12].toInt() and 0xff
        val b = bytes[13].toInt() and 0xff
        val c = bytes[14].toInt() and 0xff
        return a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
    }
    return false
}
