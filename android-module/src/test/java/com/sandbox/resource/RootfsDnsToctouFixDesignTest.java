package com.sandbox.resource;

import org.junit.Assert;
import org.junit.Test;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Teste de desenho apenas: não altera nem chama SandboxResourceManager.
 * Compara URL(hostname), que pode resolver novamente, com transporte direto
 * ao IP validado e identidade TLS/HTTP preservada separadamente.
 */
public class RootfsDnsToctouFixDesignTest {
    private static final String HOST = "example.test";
    private static final String VALIDATED_IP = "93.184.216.34";
    private static final String MALICIOUS_IP = "127.0.0.1";

    @Test
    public void hostnameUrlCanBeRebound() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        Function<String, String> resolver = ignored -> resolutions.incrementAndGet() == 1 ? VALIDATED_IP : MALICIOUS_IP;

        String validated = resolver.apply(HOST);
        String destinationUsedByHostnameUrl = resolver.apply(HOST); // representa URL(hostname).connect()

        Assert.assertEquals(VALIDATED_IP, validated);
        Assert.assertEquals(MALICIOUS_IP, destinationUsedByHostnameUrl);
        Assert.assertEquals(2, resolutions.get());
    }

    @Test
    public void validatedIpTransportPinsDestinationAndPreservesHostAndSni() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        Function<String, String> resolver = ignored -> {
            resolutions.incrementAndGet();
            return VALIDATED_IP;
        };

        String validatedIp = resolver.apply(HOST);
        Assert.assertEquals(VALIDATED_IP, validatedIp);
        Assert.assertEquals(VALIDATED_IP, InetAddress.getByName(validatedIp).getHostAddress());

        // Prova de desenho para C/D: a camada de transporte recebe o IP literal,
        // nunca o hostname. Nenhuma chamada de resolver ocorre neste passo.
        FakeValidatedHttpsTransport transport = new FakeValidatedHttpsTransport();
        SSLParameters tls = new SSLParameters();
        tls.setServerNames(Collections.singletonList(new SNIHostName(HOST)));
        transport.connect(validatedIp, 443, HOST, tls);

        // Uma segunda resolução maliciosa só seria observável se o transporte
        // voltasse a resolver HOST; o transporte fake não possui esse caminho.
        Assert.assertEquals(1, resolutions.get());
        Assert.assertEquals(VALIDATED_IP, transport.connectedIp);
        Assert.assertEquals(HOST, transport.hostHeader);
        List<javax.net.ssl.SNIServerName> names = transport.tls.getServerNames();
        Assert.assertEquals(HOST, ((SNIHostName) names.get(0)).getAsciiName());
        Assert.assertNotEquals(MALICIOUS_IP, transport.connectedIp);
    }

    private static final class FakeValidatedHttpsTransport {
        String connectedIp;
        String hostHeader;
        SSLParameters tls;

        void connect(String validatedIp, int port, String originalHost, SSLParameters tlsParameters) {
            Assert.assertEquals(443, port);
            this.connectedIp = validatedIp;       // Socket.connect(IP literal, port)
            this.hostHeader = originalHost;       // HTTP Host: example.test
            this.tls = tlsParameters;              // TLS SNI: example.test
        }
    }
}
