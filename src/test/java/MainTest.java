import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.util.FileSystemUtils;

@DisplayName("HTTP Server Stages Tests")
class MainTest {

    private static final int PORT = 4221;
    private static ExecutorService executor;
    private static Path tempDirectory;
    private static final String TEMP_DIR_NAME = "http-server-test-files";

    /**
     * Helper method to send an HTTP request to the server and read its full response.
     * This method tries to intelligently read headers and then the body based on Content-Length.
     *
     * @param request The full HTTP request string to send.
     * @return The full HTTP response string received from the server.
     * @throws IOException If a network I/O error occurs.
     */
    private String sendHttpRequest(String request) throws IOException {
        StringBuilder responseBuilder = new StringBuilder();
        try (Socket clientSocket = new Socket("localhost", PORT);
             OutputStream out = clientSocket.getOutputStream();
             BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8))) {

            // Send the request
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            clientSocket.shutdownOutput();

            String line;
            int contentLength = -1;

            // Read the response line by line
            while ((line = in.readLine()) != null) {
                responseBuilder.append(line).append("\r\n"); // Append line and CRLF

                if (line.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
                    } catch (NumberFormatException e) {
                        System.err.println(STR."Warning: Invalid Content-Length header in test client: \{line}");
                    }
                } else if (line.isEmpty()) { // Empty line signifies end of headers
                    // If Content-Length header was found, read the body immediately after this empty line
                    if (contentLength != -1) {
                        char[] bodyBuffer = new char[contentLength];
                        int bytesRead = 0;
                        int currentRead;
                        // Read characters into buffer until expected length is met or stream ends
                        while (bytesRead < contentLength && (currentRead = in.read(bodyBuffer, bytesRead, contentLength - bytesRead)) != -1) {
                            bytesRead += currentRead;
                        }
                        responseBuilder.append(new String(bodyBuffer, 0, bytesRead));
                    }
                    // If no Content-Length and headers are done, the response body is empty.
                    // The server should close the socket.
                    break; // Break here to stop reading once headers (and optional body) are processed
                }
            }
        }
        return responseBuilder.toString();
    }

    @Nested
    @DisplayName("Stage 1 Tests: Basic Routing (Root and Unknown Paths)")
    class BasicRoutingTests {

        @Test
        @DisplayName("Should return 200 OK for the root path '/'")
        void testRootPath_Returns200OK() throws IOException {
            String request = STR."""
                GET / HTTP/1.1\r
                Host: localhost:\{PORT}\r
                User-Agent: test-client\r
                Accept: */*\r
                \r
                """;
            String expectedResponse = "HTTP/1.1 200 OK\r\n\r\n";
            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should return 200 OK for root path '/'");
        }

        @Test
        @DisplayName("Should return 404 Not Found for an unknown path")
        void testUnknownPath_Returns404NotFound() throws IOException {
            String request = STR."""
                GET /some/random/path HTTP/1.1\r
                Host: localhost:\{PORT}\r
                User-Agent: test-client\r
                Accept: */*\r
                \r
                """;
            String expectedResponse = "HTTP/1.1 404 Not Found\r\n\r\n";
            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should return 404 Not Found for unknown paths");
        }

        @Test
        @DisplayName("Should return 404 Not Found for another unknown path like /favicon.ico"
        )
        void testAnotherUnknownPath_Returns404NotFound() throws IOException {
            String request = STR."""
                GET /favicon.ico HTTP/1.1\r
                Host: localhost:\{PORT}\r
                User-Agent: test-client\r
                Accept: */*\r
                \r
                """;
            String expectedResponse = "HTTP/1.1 404 Not Found\r\n\r\n";
            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should return 404 Not Found for another unknown path");
        }
    }

    @Nested
    @DisplayName("Stage 2 Tests: /echo/{str} Endpoint Functionality")
    class EchoEndpointTests {

        @Test
        @DisplayName("Should echo a simple string correctly")
        void testEchoEndpoint_SimpleString() throws IOException {
            String echoString = "hello";
            String request = STR."""
                GET /echo/\{echoString} HTTP/1.1\r
                Host: localhost:\{PORT}\r
                User-Agent: test-client\r
                Accept: */*\r
                \r
                """;
            int contentLength = echoString.getBytes(StandardCharsets.UTF_8).length;
            String expectedResponse = STR."""
                HTTP/1.1 200 OK\r
                Content-Type: text/plain\r
                Content-Length: \{contentLength}\r
                \r
                \{echoString}""";
            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should echo a simple string correctly");
        }

        @Test
        @DisplayName("Should echo an empty string correctly")
        void testEchoEndpoint_EmptyString() throws IOException {
            String echoString = "";
            String request = STR."""
                GET /echo/\{echoString} HTTP/1.1\r
                Host: localhost:\{PORT}\r
                User-Agent: test-client\r
                Accept: */*\r
                \r
                """;
            int contentLength = echoString.getBytes(StandardCharsets.UTF_8).length;
            String expectedResponse = STR."""
                HTTP/1.1 200 OK\r
                Content-Type: text/plain\r
                Content-Length: \{contentLength}\r
                \r
                \{echoString}""";
            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should echo an empty string correctly");
        }

        @Test
        @DisplayName("Should echo a string with spaces and special characters (URL-encoded)")
        void testEchoEndpoint_StringWithSpacesAndSpecialChars() throws IOException {
            // The server echoes the literal path component. If the client URL-encodes,
            // the server will echo the URL-encoded string.
            String originalString = "hello world! This is a test.";
            // URL-encode spaces and exclamation marks as a typical curl client would for the path
            String urlEncodedString = originalString.replace(" ", "%20").replace("!", "%21");

            String request = STR."""
                GET /echo/\{urlEncodedString} HTTP/1.1\r
                Host: localhost:\{PORT}\r
                User-Agent: test-client\r
                Accept: */*\r
                \r
                """;

            // The server will echo the URL-encoded string back
            int contentLength = urlEncodedString.getBytes(StandardCharsets.UTF_8).length;

            String expectedResponse = STR."""
                HTTP/1.1 200 OK\r
                Content-Type: text/plain\r
                Content-Length: \{contentLength}\r
                \r
                \{urlEncodedString}""";
            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should echo a string with spaces and special characters correctly");
        }

        @Test
        @DisplayName("Should echo Unicode strings correctly (multi-byte characters)")
        void testEchoEndpoint_WithUnicodeCharacters() throws IOException {
            // Test with Unicode characters to ensure byte length calculation is correct
            String echoString = "こんにちは世界"; // Konnichiwa Sekai (Hello World in Japanese)
            String request = STR."""
                GET /echo/\{echoString} HTTP/1.1\r
                Host: localhost:\{PORT}\r
                User-Agent: test-client\r
                Accept: */*\r
                \r
                """;
            // Japanese characters are multi-byte in UTF-8, so Content-Length should reflect byte length
            int contentLength = echoString.getBytes(StandardCharsets.UTF_8).length;

            String expectedResponse = STR."""
                HTTP/1.1 200 OK\r
                Content-Type: text/plain\r
                Content-Length: \{contentLength}\r
                \r
                \{echoString}""";
            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should echo Unicode strings correctly");
        }
    }

    @Nested
    @DisplayName("Stage 3 Tests: /user-agent Endpoint Functionality")
    class UserAgentEndpointTests {

        @Test
        @DisplayName("Should return the User-Agent header value in the response body")
        void testUserAgentEndpoint_Basic() throws IOException {
            String userAgentValue = "my-custom-browser/1.0";
            String request = STR."""
                GET /user-agent HTTP/1.1\r
                Host: localhost:\{PORT}\r
                User-Agent: \{userAgentValue}\r
                Accept: */*\r
                \r
                """;
            int contentLength = userAgentValue.getBytes(StandardCharsets.UTF_8).length;
            String expectedResponse = STR."""
                HTTP/1.1 200 OK\r
                Content-Type: text/plain\r
                Content-Length: \{contentLength}\r
                \r
                \{userAgentValue}""";
            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should return the User-Agent value in the response body.");
        }

        @Test
        @DisplayName("Should handle User-Agent with spaces and special characters")
        void testUserAgentEndpoint_ComplexUserAgent() throws IOException {
            String userAgentValue = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
            String request = STR."""
                GET /user-agent HTTP/1.1\r
                Host: localhost:\{PORT}\r
                User-Agent: \{userAgentValue}\r
                Accept: */*\r
                \r
                """;
            int contentLength = userAgentValue.getBytes(StandardCharsets.UTF_8).length;
            String expectedResponse = STR."""
                HTTP/1.1 200 OK\r
                Content-Type: text/plain\r
                Content-Length: \{contentLength}\r
                \r
                \{userAgentValue}""";
            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should correctly handle complex User-Agent strings.");
        }

        @Test
        @DisplayName("Should return empty body if User-Agent header is missing")
        void testUserAgentEndpoint_MissingUserAgent() throws IOException {
            String userAgentValue = ""; // Expected empty string for missing header
            String request = STR."""
                GET /user-agent HTTP/1.1\r
                Host: localhost:\{PORT}\r
                Accept: */*\r
                \r
                """; // No User-Agent header included
            int contentLength = userAgentValue.getBytes(StandardCharsets.UTF_8).length;
            String expectedResponse = STR."""
                HTTP/1.1 200 OK\r
                Content-Type: text/plain\r
                Content-Length: \{contentLength}\r
                \r
                \{userAgentValue}""";
            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should return an empty body if User-Agent header is missing.");
        }

        @Test
        @DisplayName("Should correctly parse User-Agent header regardless of casing (e.g., 'user-agent')")
        void testUserAgentEndpoint_LowercaseHeader() throws IOException {
            String userAgentValue = "lowercase-ua/2.0";
            String request = STR."""
                GET /user-agent HTTP/1.1\r
                Host: localhost:\{PORT}\r
                user-agent: \{userAgentValue}\r
                Accept: */*\r
                \r
                """; // Header name in lowercase
            int contentLength = userAgentValue.getBytes(StandardCharsets.UTF_8).length;
            String expectedResponse = STR."""
                HTTP/1.1 200 OK\r
                Content-Type: text/plain\r
                Content-Length: \{contentLength}\r
                \r
                \{userAgentValue}""";
            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should parse 'user-agent' header correctly.");
        }

        @Test
        @DisplayName("Should correctly parse User-Agent header regardless of casing (e.g., 'USER-AGENT')")
        void testUserAgentEndpoint_UppercaseHeader() throws IOException {
            String userAgentValue = "UPPERCASE-UA/3.0";
            String request = STR."""
                GET /user-agent HTTP/1.1\r
                Host: localhost:\{PORT}\r
                USER-AGENT: \{userAgentValue}\r
                Accept: */*\r
                \r
                """; // Header name in uppercase
            int contentLength = userAgentValue.getBytes(StandardCharsets.UTF_8).length;
            String expectedResponse = STR."""
                HTTP/1.1 200 OK\r
                Content-Type: text/plain\r
                Content-Length: \{contentLength}\r
                \r
                \{userAgentValue}""";
            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should parse 'USER-AGENT' header correctly.");
        }
    }

    @Nested
    @DisplayName("Stage 4 Tests: Concurrency & Malformed Requests")
    class ConcurrencyAndMalformedTests {

        // This test simulates the exact scenario you described with nc
        @Test
        @DisplayName("Should handle multiple concurrent GET / requests correctly (nc-like scenario - Structured Concurrency)")
        void testSpecificConcurrentGetRootRequests() throws Exception {
            final int numberOfConcurrentClients = 10; // Simulating 10 concurrent connections
            final String request = STR."""
                GET / HTTP/1.1\r
                Host: localhost:\{PORT}\r
                \r
                """;
            final String expectedResponse = "HTTP/1.1 200 OK\r\n\r\n";

            try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
                for (int i = 0; i < numberOfConcurrentClients; i++) {
                    final int clientId = i;
                    scope.fork(() -> {
                        System.out.println(STR."Client \{clientId}: Sending GET / request.");
                        String actualResponse = sendHttpRequest(request);
                        System.out.println(STR."Client \{clientId}: Received response. Verifying...");
                        assertEquals(expectedResponse, actualResponse, STR."Client \{clientId} failed: Incorrect response for GET /");
                        return STR."Client \{clientId} successful.";
                    });
                }
                scope.join();
                scope.throwIfFailed();
            }

            System.out.println(STR."Successfully processed \{numberOfConcurrentClients} concurrent GET / requests using Structured Concurrency.");
        }
    }

    @Nested
    @DisplayName("Stage 5 Tests: /files/{filename} Endpoint Functionality")
    class FileEndpointTests {
        @AfterAll
        static void cleanupFiles() throws IOException {
            FileSystemUtils.deleteRecursively(tempDirectory);
            System.out.println("Temporary files cleaned up from: " + tempDirectory.toAbsolutePath());
        }

        @Test
        @DisplayName("Should return 200 OK with file content for an existing file")
        void testExistingFile() throws IOException {
            String filename = "test_file_1.txt";
            String fileContent = "This is a test file content.";
            Path filePath = tempDirectory.resolve(filename);
            Files.writeString(filePath, fileContent); // Create the file

            String request = STR."""
                GET /files/\{filename} HTTP/1.1\r
                Host: localhost:\{PORT}\r
                \r
                """;

            byte[] contentBytes = fileContent.getBytes(StandardCharsets.UTF_8);
            String expectedResponseHeaders = STR."""
                HTTP/1.1 200 OK\r
                Content-Type: application/octet-stream\r
                Content-Length: \{contentBytes.length}\r
                \r
                """;
            String expectedResponse = expectedResponseHeaders + fileContent; // Combine headers and body

            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should return 200 OK with correct file content.");
        }

        @Test
        @DisplayName("Should return 404 Not Found for a non-existent file")
        void testNonExistentFile() throws IOException {
            String filename = "non_existent_file.xyz"; // This file should not exist
            String request = STR."""
                GET /files/\{filename} HTTP/1.1\r
                Host: localhost:\{PORT}\r
                \r
                """;

            String expectedResponse = "HTTP/1.1 404 Not Found\r\n\r\n";
            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should return 404 Not Found for non-existent files.");
        }

        @Test
        @DisplayName("Should return 200 OK for an empty file")
        void testEmptyFile() throws IOException {
            String filename = "empty.txt";
            String fileContent = "";
            Path filePath = tempDirectory.resolve(filename);
            Files.writeString(filePath, fileContent); // Create an empty file

            String request = STR."""
                GET /files/\{filename} HTTP/1.1\r
                Host: localhost:\{PORT}\r
                \r
                """;

            byte[] contentBytes = fileContent.getBytes(StandardCharsets.UTF_8); // length will be 0
            String expectedResponseHeaders = STR."""
                HTTP/1.1 200 OK\r
                Content-Type: application/octet-stream\r
                Content-Length: \{contentBytes.length}\r
                \r
                """;
            String expectedResponse = expectedResponseHeaders + fileContent;

            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should return 200 OK with empty content for an empty file.");
        }

        @Test
        @DisplayName("Should handle file names with special characters (URL-encoded)")
        void testFileWithSpecialCharacters() throws IOException {
            String originalFilename = "file with spaces & symbols!.txt";
            String urlEncodedFilename = originalFilename
                .replace(" ", "%20")
                .replace("&", "%26")
                .replace("!", "%21");
            String fileContent = "Content for special file.";
            Path filePath = tempDirectory.resolve(originalFilename); // Create file with actual name
            Files.writeString(filePath, fileContent);

            String request = STR."""
                GET /files/\{urlEncodedFilename} HTTP/1.1\r
                Host: localhost:\{PORT}\r
                \r
                """;

            byte[] contentBytes = fileContent.getBytes(StandardCharsets.UTF_8);
            String expectedResponseHeaders = STR."""
                HTTP/1.1 200 OK\r
                Content-Type: application/octet-stream\r
                Content-Length: \{contentBytes.length}\r
                \r
                """;
            String expectedResponse = expectedResponseHeaders + fileContent;

            String actualResponse = sendHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should handle URL-encoded filenames.");
        }
    }

    @BeforeAll
    static void setup() throws InterruptedException, IOException {
        Path desiredTempDirectory = Paths.get(System.getProperty("java.io.tmpdir"), TEMP_DIR_NAME);
        if (Files.exists(desiredTempDirectory)) {
            tempDirectory = desiredTempDirectory;
            System.out.println("Using existing temporary directory: " + tempDirectory.toAbsolutePath());
        } else {
            tempDirectory = Files.createDirectories(desiredTempDirectory);
            System.out.println("Created new temporary directory: " + tempDirectory.toAbsolutePath());
        }

        executor = Executors.newVirtualThreadPerTaskExecutor();
        // Pass the --directory argument to the Main.main method
        executor.submit(() -> Main.main(new String[] {"--directory", tempDirectory.toAbsolutePath().toString()}));
        TimeUnit.MILLISECONDS.sleep(500); // Give server time to restart and pick up new arg
        System.out.println("Server restarted with --directory flag.");
    }

    @AfterAll
    static void tearDown(){
        // Shut down the server thread after all tests are done
        if (executor != null) {
            executor.shutdownNow(); // Attempt to stop the server gracefully
            System.out.println("Server thread shut down.");
        }
    }

}
