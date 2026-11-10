package com.posturestudio.booking.auth;

import java.net.InetAddress;
import java.net.UnknownHostException;

import jakarta.servlet.http.HttpServletRequest;

// The address of the client, for the per-IP limit (guide 24, 6.4). Behind Caddy, Tomcat has already
// replaced it with the one in X-Forwarded-For, trusting only a proxy on a private network
// (server.forward-headers-strategy=native). The header is never read here: anyone can write it.
// getRemoteAddr is always an IP literal, so getByName does not ask any DNS
final class ClientAddress {

    private ClientAddress() {
    }

    static InetAddress of(HttpServletRequest http) {
        try {
            return InetAddress.getByName(http.getRemoteAddr());
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Not an IP address: " + http.getRemoteAddr(), e);
        }
    }
}
