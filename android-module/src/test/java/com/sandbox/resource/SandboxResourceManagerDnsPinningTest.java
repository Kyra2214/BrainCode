package com.sandbox.resource;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

public class SandboxResourceManagerDnsPinningTest {
    @Test
    public void managerPassesValidatedIpAndOriginalHostWithoutSecondResolution() throws Exception {
        File target = Files.createTempFile("rootfs-pinned", ".tar.gz").toFile();
        AtomicInteger resolutions = new AtomicInteger();
        HttpURLConnection[] seen = new HttpURLConnection[1];
        try {
            RootfsManifest manifest = new RootfsManifest("1.0.0", "arm64-v8a", "test", "https://example.test/rootfs.tar.gz", 0, "0".repeat(64), "1.0.0", "", "", "", "", false);
            SandboxResourceManager manager = new SandboxResourceManager(
                target,
                null,
                url -> {
                    Assert.assertEquals("93.184.216.34", url.getHost());
                    seen[0] = new FakeConnection(url);
                    return seen[0];
                },
                host -> {
                    Assert.assertEquals("example.test", host);
                    resolutions.incrementAndGet();
                    try {
                        return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{93, (byte)184, (byte)216, 34})};
                    } catch (java.net.UnknownHostException error) {
                        throw new AssertionError(error);
                    }
                }
            );

            manager.ensureAvailable(manifest, null);
            Assert.assertEquals(1, resolutions.get());
            Assert.assertEquals("93.184.216.34", seen[0].getURL().getHost());
            Assert.assertEquals("example.test", ((FakeConnection) seen[0]).hostHeader);
        } finally {
            target.delete();
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        String hostHeader;
        FakeConnection(URL url) { super(url); }
        @Override public void setRequestProperty(String key, String value) {
            if ("Host".equalsIgnoreCase(key)) hostHeader = value;
        }
        @Override public void connect() { connected = true; }
        @Override public void disconnect() { connected = false; }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return HTTP_OK; }
        @Override public java.io.InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
    }
}
