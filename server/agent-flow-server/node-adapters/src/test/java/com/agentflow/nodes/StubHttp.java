package com.agentflow.nodes;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

/** 测试用本地 HTTP 桩（JDK 内置 HttpServer，不引 WireMock 等额外依赖）。 */
public final class StubHttp implements AutoCloseable {

    private final HttpServer server;
    private final List<String> requestLog = Collections.synchronizedList(new ArrayList<>());

    public StubHttp(HttpHandler handler) throws IOException {
        server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestLog.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            handler.handle(exchange);
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 形如 "GET /cgi-bin/token" 的调用序列，用于钉住 API 调用顺序与次数。 */
    public List<String> requestLog() {
        return new ArrayList<>(requestLog);
    }

    public int countOf(String methodAndPath) {
        return (int) requestLog.stream().filter(e -> e.equals(methodAndPath)).count();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void respondBytes(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 取一个确定没人监听的端口 URL：连接拒绝场景用。 */
    public static String closedPortUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }
}
