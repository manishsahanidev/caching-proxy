import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

public class CachingProxy {
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
                    return;
            }
        }

        if (clearCache) {
            System.out.println("Clearing cache...");
            // Logic to clear cache later
            return;
        }

        if (port == null || origin == null) {
            System.err.println("Error: missing required arguments: --port and --origin");
            return;
        }

        System.out.println("Starting proxy server on port " + port);
        System.out.println("Forwarding requests to origin: " + origin);

        // Http server
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ProxyHandler());
        server.setExecutor(null); // default executor
        server.start();

        System.out.println("Server is listening on http://localhost:" + port);
    }

    static class ProxyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String responseText = "Hello from proxy!";
            exchange.sendResponseHeaders(200, responseText.length());
            OutputStream os = exchange.getResponseBody();
            os.write(responseText.getBytes());
            os.close();
        }
    }
}
