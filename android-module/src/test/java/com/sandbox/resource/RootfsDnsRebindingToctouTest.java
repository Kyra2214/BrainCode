package com.sandbox.resource;

import org.junit.Assert;
import org.junit.Test;

import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Controlled DNS rebinding/TOCTOU audit; no real DNS or external server. */
public class RootfsDnsRebindingToctouTest {
    @Test
    public void hostnameCanValidatePublicIpAndConnectToPrivateIp() throws Exception {
        AtomicInteger resolutionCount = new AtomicInteger();
        List<InetAddress> validatedAddresses = new ArrayList<>();
        InetAddress[] connectedAddress = new InetAddress[1];

        java.util.function.Function<String, InetAddress[]> resolve = host -> {
            Assert.assertEquals("example.test", host);
            try {
                if (resolutionCount.incrementAndGet() == 1) {
                    return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{93, (byte)184, (byte)216, 34})};
                }
                return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1})};
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        };

        InetAddress[] firstResolution = resolve.apply("example.test");
        validatedAddresses.add(firstResolution[0]);
        Assert.assertFalse(validatedAddresses.get(0).isLoopbackAddress());
        Assert.assertFalse(validatedAddresses.get(0).isSiteLocalAddress());

        URLConnection connection = new URLConnection(new URL("https://example.test/rootfs.tar.gz")) {
            @Override public void connect() {
                connectedAddress[0] = resolve.apply(url.getHost())[0];
            }
        };
        connection.connect();

        Assert.assertEquals("93.184.216.34", validatedAddresses.get(0).getHostAddress());
        Assert.assertEquals("127.0.0.1", connectedAddress[0].getHostAddress());
        Assert.assertNotEquals(validatedAddresses.get(0), connectedAddress[0]);
    }
}
