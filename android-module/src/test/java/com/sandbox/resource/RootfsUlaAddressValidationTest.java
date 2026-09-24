package com.sandbox.resource;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

/** Auditoria isolada do predicado usado por SandboxResourceManager. */
public class RootfsUlaAddressValidationTest {
    private static final List<String> ULA = Arrays.asList(
        "fc00::1",
        "fd00::1",
        "fd12:3456:789a::1",
        "2001:4860:4860::8888"
    );

    @Test
    public void reportsCurrentUlaValidationBehavior() throws Exception {
        for (String literal : ULA) {
            InetAddress address = InetAddress.getByName(literal);
            boolean loopback = address.isLoopbackAddress();
            boolean siteLocal = address.isSiteLocalAddress();
            boolean linkLocal = address.isLinkLocalAddress();
            boolean anyLocal = address.isAnyLocalAddress();
            boolean multicast = address.isMulticastAddress();
            boolean mappedPrivate = isMappedPrivate(address.getAddress());
            boolean ula = isUla(address.getAddress());
            boolean acceptedByCurrentPredicate = !loopback && !siteLocal && !linkLocal
                && !anyLocal && !multicast && !mappedPrivate && !ula;

            System.out.printf(
                "%s loopback=%s siteLocal=%s linkLocal=%s ula=%s finalAccepted=%s%n",
                literal, loopback, siteLocal, linkLocal, ula, acceptedByCurrentPredicate
            );
        }
    }

    private static boolean isMappedPrivate(byte[] bytes) {
        if (bytes.length != 16) return false;
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        if (bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) return false;
        int a = bytes[12] & 0xff;
        int b = bytes[13] & 0xff;
        return a == 10 || a == 127 || (a == 169 && b == 254)
            || (a == 172 && b >= 16 && b <= 31)
            || (a == 192 && b == 168);
    }

    private static boolean isUla(byte[] bytes) {
        return bytes.length == 16 && (Byte.toUnsignedInt(bytes[0]) >= 0xfc && Byte.toUnsignedInt(bytes[0]) <= 0xfd);
    }
}
