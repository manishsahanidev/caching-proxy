import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CachingProxy {

    private static final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL = 5 * 60 * 1000; // 5 minutes in milliseconds
    private static String originServer;

    public static void main(String[] args) throws IOException {
        Integer port = null;
        String origin = null;
        boolean clearCache = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port":
                    if (i + 1 < args.length) {
                        port = Integer.parseInt(args[++i]);
                    } else {
                        System.err.println("Error: --port requires a value.");
                        return;
                    }
                    break;

                case "--origin":
                    if (i + 1 < args.length) {
                        origin = args[++i];
                    } else {
                        System.err.println("Error: --origin requires a value.");
                        return;
                    }
                    break;

                case "--clear-cache":
                    clearCache = true;
                    break;

                default:
                    System.err.println("Error: Unknown argument " + args[i]);
                    printUsage();
                    return;
            }
        }

        if (clearCache) {
            System.out.println("Clearing cache...");
            int clearedEntries = cache.size();
            cache.clear();
            System.out.println("Cache cleared successfully. " + clearedEntries + " entries removed.");
            return;
        }

        if (port == null || origin == null) {
            System.err.println("Error: missing required arguments: --port and --origin");
            printUsage();
            return;
        }

        // Store origin server for use in handler
        originServer = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;

        System.out.println("Starting caching proxy server on port " + port);
        System.out.println("Forwarding requests to origin: " + originServer);

        // HTTP server
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ProxyHandler());
        server.setExecutor(null);
        server.start();

        System.out.println("Caching proxy server is listening on http://localhost:" + port);

        // shutdown hook to stop the server effectively
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down caching proxy server...");
            server.stop(0);
        }));
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java CachingProxy --port <number> --origin <url>");
        System.out.println("  java CachingProxy --clear-cache");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java CachingProxy --port 3000 --origin http://dummyjson.com");
        System.out.println("  java CachingProxy --clear-cache");
    }

    // Cache entry class to store response data with timestamp
    static class CacheEntry {
        private final String responseBody;
        private final int statusCode;
        private final Map<String, List<String>> headers;
        private final long timestamp;

        public CacheEntry(String responseBody, int statusCode, Map<String, List<String>> headers) {
            this.responseBody = responseBody;
            this.statusCode = statusCode;
            this.headers = headers;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL;
        }

        public String getResponseBody() {
            return responseBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public Map<String, List<String>> getHeaders() {
            return headers;
        }

        public long getAge() {
            return System.currentTimeMillis() - timestamp;
        }
    }

    static class ProxyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().toString();

            // Create cache key from method and path
            String cacheKey = method + " " + path;

            System.out.println("Incoming request: " + method + " " + path);

            // Check cache first
            CacheEntry cachedEntry = cache.get(cacheKey);
            if (cachedEntry != null && !cachedEntry.isExpired()) {
                // Serve from cache
                System.out.println("Cache HIT for: " + cacheKey + " (age: " +
                        (cachedEntry.getAge() / 1000) + "s)");
                serveFromCache(exchange, cachedEntry);
                return;
            }

            // Cache miss or expired - forward to origin server
            if (cachedEntry != null && cachedEntry.isExpired()) {
                cache.remove(cacheKey); // Remove expired entry
                System.out.println("Cache EXPIRED for: " + cacheKey + " - Removing and forwarding to origin");
            } else {
                System.out.println("Cache MISS for: " + cacheKey + " - Forwarding to origin");
            }

            forwardToOrigin(exchange, cacheKey, method, path);
        }

        private void serveFromCache(HttpExchange exchange, CacheEntry cachedEntry) throws IOException {
            // Copy cached headers to response
            Headers responseHeaders = exchange.getResponseHeaders();
            for (Map.Entry<String, List<String>> header : cachedEntry.getHeaders().entrySet()) {
                String key = header.getKey();
                if (key != null && !shouldSkipHeader(key)) {
                    responseHeaders.put(key, header.getValue());
                }
            }

            // Add cache indicator headers
            responseHeaders.set("X-Cache", "HIT");
            responseHeaders.set("X-Cache-Age", String.valueOf(cachedEntry.getAge() / 1000));

            // Send cached response
            byte[] responseBytes = cachedEntry.getResponseBody().getBytes();
            exchange.sendResponseHeaders(cachedEntry.getStatusCode(), responseBytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }

        private void forwardToOrigin(HttpExchange exchange, String cacheKey, String method, String path) {
            try {
                String fullUrl = originServer + path;
                System.out.println("Forwarding to: " + fullUrl);

                URL url = new URL(fullUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                connection.setRequestMethod(method);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);

                // Copy request headers (excluding hop-by-hop headers)
                Headers requestHeaders = exchange.getRequestHeaders();
                for (String headerKey : requestHeaders.keySet()) {
                    if (!shouldSkipHeader(headerKey)) {
                        List<String> values = requestHeaders.get(headerKey);
                        if (!values.isEmpty()) {
                            connection.setRequestProperty(headerKey, values.get(0));
                        }
                    }
                }

                // Handle request body for POST/PUT requests
                if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
                    connection.setDoOutput(true);
                    try (InputStream requestBody = exchange.getRequestBody();
                         OutputStream connectionOutput = connection.getOutputStream()) {
                        requestBody.transferTo(connectionOutput);
                    }
                }

                // Get response
                int statusCode = connection.getResponseCode();
                Map<String, List<String>> originHeaders = connection.getHeaderFields();

                // Read response body
                String responseBody;
                try (InputStream inputStream = statusCode >= 400 ?
                        connection.getErrorStream() : connection.getInputStream()) {

                    if (inputStream != null) {
                        responseBody = new String(inputStream.readAllBytes());
                    } else {
                        responseBody = "";
                    }
                }

                // Cache successful responses (2xx and 3xx status codes)
                if (statusCode >= 200 && statusCode < 400 && "GET".equalsIgnoreCase(method)) {
                    cache.put(cacheKey, new CacheEntry(responseBody, statusCode, originHeaders));
                    System.out.println("Response cached for: " + cacheKey);
                }

                // Send response to client
                sendResponse(exchange, responseBody, statusCode, originHeaders, false);

            } catch (IOException e) {
                System.err.println("Error forwarding request: " + e.getMessage());
                sendErrorResponse(exchange, 502, "Bad Gateway: " + e.getMessage());
            }
        }

        private void sendResponse(HttpExchange exchange, String responseBody, int statusCode,
                                  Map<String, List<String>> originHeaders, boolean isFromCache) throws IOException {

            // Copy response headers (excluding hop-by-hop headers)
            Headers responseHeaders = exchange.getResponseHeaders();
            for (Map.Entry<String, List<String>> header : originHeaders.entrySet()) {
                String key = header.getKey();
                if (key != null && !shouldSkipHeader(key)) {
                    responseHeaders.put(key, header.getValue());
                }
            }

            // Add cache indicator headers
            responseHeaders.set("X-Cache", isFromCache ? "HIT" : "MISS");
            responseHeaders.set("Via", "1.1 CachingProxy");

            // Send response
            byte[] responseBytes = responseBody.getBytes();
            exchange.sendResponseHeaders(statusCode, responseBytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }

        private void sendErrorResponse(HttpExchange exchange, int statusCode, String message) {
            try {
                Headers responseHeaders = exchange.getResponseHeaders();
                responseHeaders.set("Content-Type", "text/plain");
                responseHeaders.set("X-Cache", "MISS");

                byte[] responseBytes = message.getBytes();
                exchange.sendResponseHeaders(statusCode, responseBytes.length);

                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            } catch (IOException e) {
                System.err.println("Error sending error response: " + e.getMessage());
            }
        }

        private boolean shouldSkipHeader(String headerName) {
            if (headerName == null) return true;

            String normalized = headerName.toLowerCase();
            return normalized.equals("connection") ||
                    normalized.equals("keep-alive") ||
                    normalized.equals("proxy-authenticate") ||
                    normalized.equals("proxy-authorization") ||
                    normalized.equals("te") ||
                    normalized.equals("trailers") ||
                    normalized.equals("transfer-encoding") ||
                    normalized.equals("upgrade") ||
                    normalized.equals("host");
        }
    }
}