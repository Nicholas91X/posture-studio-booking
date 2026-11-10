package com.posturestudio.booking.notification;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

// A push service on localhost, with the HTTP server of the JDK (guide 28, part 11): it records what it
// receives and answers what the test asks, per device (201 by default, 410 for a device that no longer
// exists, 500 for a service that is down). The rows pointing here are inserted with SQL: @PushEndpoint
// guards the door, and would refuse http://localhost, as it must
final class FakePushService implements AutoCloseable {

    record Received(String device, Map<String, String> headers, byte[] body) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();

    FakePushService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/push/", this::handle);
        server.start();
    }

    String endpoint(String device) {
        return "http://localhost:" + server.getAddress().getPort() + "/push/" + device;
    }

    void answer(String device, int status) {
        statuses.put(device, status);
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    void clear() {
        received.clear();
        statuses.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String device = exchange.getRequestURI().getPath().substring("/push/".length());
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        Map<String, String> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.getFirst()));
        received.add(new Received(device, headers, body));
        exchange.sendResponseHeaders(statuses.getOrDefault(device, 201), -1);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
