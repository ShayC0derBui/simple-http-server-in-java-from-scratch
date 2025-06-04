import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import exceptions.BadRequestException;

public class Main {
    public static void main(String[] args) {
        System.out.println("Logs from your program will appear here!");

        try (ServerSocket serverSocket = new ServerSocket(4221)) {
            serverSocket.setReuseAddress(true);
            System.out.println("Server listening on port 4221. Waiting for connections...");

            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("\nAccepted new connection from client: " + clientSocket.getInetAddress());
                    handleConnection(clientSocket);
                } catch (IOException e) {
                    System.out.println("Error accepting client connection: " + e.getMessage());
                }
            }

        } catch (IOException e) {
            System.out.println("Server socket error: " + e.getMessage());
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
            String response = "HTTP/1.1 400 Bad Request\r\nContent-Length: " + message.length() + "\r\n\r\n" + message;
            outputStream.write(response.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
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

            // --- Determine the appropriate HTTP response based on the request path ---
            String response = switch (requestPath) {
                case "/" -> {
                    System.out.println("Path is '/', sending 200 OK (simple).");
                    yield "HTTP/1.1 200 OK\r\n\r\n";
                }
                case String p when p.startsWith("/echo/") -> {
                    String echoString = p.substring("/echo/".length());
                    byte[] responseBodyBytes = echoString.getBytes(StandardCharsets.UTF_8);
                    int contentLength = responseBodyBytes.length;

                    System.out.println("Path is /echo/, echoing: '" + echoString + "', sending 200 OK with body.");
                    yield STR."""
                        HTTP/1.1 200 OK\r
                        Content-Type: text/plain\r
                        Content-Length: \{contentLength}\r
                        \r
                        \{echoString}""";
                }
                case "/user-agent" -> {
                    String userAgent = headers.getOrDefault("user-agent", "");
                    byte[] responseBodyBytes = userAgent.getBytes(StandardCharsets.UTF_8);
                    int contentLength = responseBodyBytes.length;

                    System.out.println("Path is /user-agent, User-Agent: '" + userAgent + "', sending 200 OK with body.");
                    yield STR."""
                        HTTP/1.1 200 OK\r
                        Content-Type: text/plain\r
                        Content-Length: \{contentLength}\r
                        \r
                        \{userAgent}""";
                }
                default -> {
                    System.out.println("Path is '" + requestPath + "', sending 404 Not Found.");
                    yield "HTTP/1.1 404 Not Found\r\n\r\n";
                }
            };

            // Send the constructed response back to the client
            outputStream.write(response.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            System.out.println("Response sent.");

        }
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
                // Malformed header line (e.g., "invalid header without colon")
                System.out.println("Malformed header line: " + headerLine);
                // For now, just ignore malformed single header lines
            }
        }
        System.out.println("Finished reading headers. Parsed headers: " + headers);
        return headers;
    }
}