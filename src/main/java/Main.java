import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import exceptions.BadRequestException;

public class Main {
    private static String filesDirectory;

    public static void main(String[] args) {
        System.out.println("Logs from your program will appear here!");

        getFileDirectory(args);

        // Using try-with-resources for serverSocket and executorService ensures they are closed
        try (ServerSocket serverSocket = new ServerSocket(4221);
             ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()
        ) {
            serverSocket.setReuseAddress(true);
            System.out.println("Server listening on port 4221. Waiting for connections...");

            while (true) {
                try {
                    final Socket clientSocket = serverSocket.accept();
                    System.out.println("\nAccepted new connection from client: " + clientSocket.getInetAddress());
                    executorService.submit(() -> handleConnection(clientSocket));
                } catch (IOException e) {
                    System.out.println("Error accepting client connection: " + e.getMessage());
                }
            }

        } catch (IOException e) {
            System.out.println("Server socket error: " + e.getMessage());
        }
    }

    private static void getFileDirectory(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--directory".equals(args[i]) && i + 1 < args.length) {
                filesDirectory = args[i + 1];
                System.out.println("Serving files from directory: " + filesDirectory);
                break;
            }
        }
    }

    private static void handleConnection(Socket clientSocket) {
        try {
            handleClient(clientSocket);
        } catch (BadRequestException e) {
            System.out.println("Bad request received: " + e.getMessage());
            sendBadRequestResponse(clientSocket, e.getMessage());
        } catch (IOException e) {
            System.out.println("Error handling client: " + e.getMessage());
        } finally {
            closeSocketQuietly(clientSocket);
        }
    }

    private static void sendBadRequestResponse(Socket clientSocket, String message) {
        if (clientSocket == null || clientSocket.isClosed()) {
            return;
        }

        try (OutputStream outputStream = clientSocket.getOutputStream()) {
            String headers = "HTTP/1.1 400 Bad Request\r\nContent-Length: " + message.length() + "\r\n\r\n";
            Response errorResponse = new Response(headers, message.getBytes(StandardCharsets.UTF_8));
            sendResponseToClient(outputStream, errorResponse);
        } catch (IOException e) {
            System.err.println("Error sending 400 response: " + e.getMessage());
        }
    }

    private static void closeSocketQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignore) {
            }
        }
    }

    private static void handleClient(Socket socket) throws IOException {
        try (InputStream inputStream = socket.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
             OutputStream outputStream = socket.getOutputStream()) {

            String requestPath = getRequestPath(reader);
            Map<String, String> headers = getHeaders(reader);

            Response response = switch (requestPath) {
                case "/" -> processDefaultPath();
                case String p when
                    p.startsWith("/echo/") -> processEchoPath(p);
                case "/user-agent" -> processUserAgentPath(headers);
                case String p when
                    p.startsWith("/files/") -> processFilePath(p);
                default -> process404(requestPath);
            };

            sendResponseToClient(outputStream, response);
        }
    }

    private static void sendResponseToClient(OutputStream outputStream, Response response) throws IOException {
        outputStream.write(response.headers().getBytes(StandardCharsets.UTF_8));
        if (response.body() != null) {
            outputStream.write(response.body());
        }
        outputStream.flush();
        System.out.println("Response sent.");
    }

    private static Response processDefaultPath() {
        System.out.println("Path is '/', sending 200 OK (simple).");
        return new Response("HTTP/1.1 200 OK\r\n\r\n", null);
    }

    private static Response processEchoPath(String p) {
        String echoString = p.substring("/echo/".length());
        byte[] responseBodyBytes = echoString.getBytes(StandardCharsets.UTF_8);
        int contentLength = responseBodyBytes.length;

        System.out.println("Path is /echo/, echoing: '" + echoString + "', sending 200 OK with body.");
        String headers = STR."""
            HTTP/1.1 200 OK\r
            Content-Type: text/plain\r
            Content-Length: \{contentLength}\r
            \r
            """;
        return new Response(headers, responseBodyBytes);
    }

    private static Response processUserAgentPath(Map<String, String> headers) {
        String userAgent = headers.getOrDefault("user-agent", "");
        byte[] responseBodyBytes = userAgent.getBytes(StandardCharsets.UTF_8);
        int contentLength = responseBodyBytes.length;

        System.out.println("Path is /user-agent, User-Agent: '" + userAgent + "', sending 200 OK with body.");
        String responseHeaders = STR."""
            HTTP/1.1 200 OK\r
            Content-Type: text/plain\r
            Content-Length: \{contentLength}\r
            \r
            """;
        return new Response(responseHeaders, responseBodyBytes);
    }

    private static Response processFilePath(String p) throws IOException {
        if (filesDirectory == null) {
            System.err.println("Error: --directory was not specified, cannot serve files.");
            return new Response("HTTP/1.1 500 Internal Server Error\r\n\r\n", null);
        }

        String filename = p.substring("/files/".length());
        filename = java.net.URLDecoder.decode(filename, StandardCharsets.UTF_8)
            .replace("../", ""); // prevent directory traversal attacks
        Path filePath = Paths.get(filesDirectory, filename);

        if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
            byte[] fileBytes = Files.readAllBytes(filePath);
            String headers = STR."""
                HTTP/1.1 200 OK\r
                Content-Type: application/octet-stream\r
                Content-Length: \{fileBytes.length}\r
                \r
                """;
            System.out.println(STR."Served file: \{filename}, size: \{fileBytes.length} bytes.");
            return new Response(headers, fileBytes);
        } else {
            System.out.println("File not found or not accessible: " + filename);
            return process404(p);
        }
    }

    private static Response process404(String requestPath) {
        System.out.println("Path is '" + requestPath + "', sending 404 Not Found.");
        return new Response("HTTP/1.1 404 Not Found\r\n\r\n", null);
    }

    private static String getRequestPath(BufferedReader reader) throws IOException {
        String requestLine = reader.readLine(); // Read the first line of the HTTP request
        if (requestLine == null || requestLine.isEmpty()) {
            System.out.println("Received empty or null request line. Throwing BadRequestException.");
            throw new BadRequestException("Request line was null or empty."); // Throw specific exception
        }
        System.out.println("Received request line: " + requestLine);

        String requestPath;
        String[] parts = requestLine.split(" ");
        if (parts.length > 1) {
            requestPath = parts[1]; // Extract the request target (path)
        } else {
            // Handle cases like "GET HTTP/1.1" or "GET" - these are malformed request lines
            System.out.println("Malformed request line (missing path or version): " + requestLine);
            throw new BadRequestException("Malformed request line: " + requestLine);
        }

        System.out.println("Extracted request path: " + requestPath);
        return requestPath;
    }

    private static Map<String, String> getHeaders(BufferedReader reader) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String headerLine;
        while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
            System.out.println("Received header: " + headerLine);
            int colonIndex = headerLine.indexOf(':');
            if (colonIndex > 0) {
                String headerName = headerLine.substring(0, colonIndex).trim().toLowerCase();
                String headerValue = headerLine.substring(colonIndex + 1).trim();
                headers.put(headerName, headerValue);
            } else {
                System.out.println("Malformed header line: " + headerLine);
            }
        }
        System.out.println("Finished reading headers. Parsed headers: " + headers);
        return headers;
    }

    private record Response(String headers, byte[] body) {
    }
}