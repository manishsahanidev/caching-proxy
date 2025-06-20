public class CachingProxy {
    public static void main(String[] args) {
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
        }

        if (port == null || origin == null) {
            System.err.println("Error: missing required arguments: --port and --origin");
            return;
        }

        System.out.println("Starting proxy server on port " + port);
        System.out.println("Forwarding requests to origin: " + origin);

    }
}
