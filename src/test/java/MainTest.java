import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.util.FileSystemUtils;
import request.HttpRequest;
import response.HttpResponse;
import response.HttpStatus;

@DisplayName("HTTP Server Stages Tests")
class MainTest {

    private static final int PORT = 4221;
    private static final String TEMP_DIR_NAME = "http-server-test-files";
    private static ExecutorService executor;
    private static Path tempDirectory;

    /**
     * Helper method to send an HTTP request and read its full response,
     * parsing it into an HttpResponse object, using an EXISTING socket.
     * This is suitable for testing persistent connections.
     *
     * @param clientSocket The already established Socket connection to use.
     * @param request      The full HTTP request string to send.
     * @return An HttpResponse object containing status line, headers, and raw body.
     * @throws IOException If a network I/O error occurs.
     */
    public static HttpResponse sendRequestOnExistingSocket(Socket clientSocket, String request) throws IOException {
        String statusLine;
        Map<String, String> headers = new LinkedHashMap<>();
        byte[] bodyBytes = new byte[0];

        // Do NOT create new streams in try-with-resources if you want to reuse them across calls
        // Instead, get them once from the socket and ensure they are buffered.
        // For simplicity in this helper, we re-wrap, but in a real client, manage streams carefully.
        OutputStream out = clientSocket.getOutputStream();
        BufferedInputStream in = new BufferedInputStream(clientSocket.getInputStream()); // Ensure buffered for robust reading

        // 1. Send the request
        out.write(request.getBytes(StandardCharsets.UTF_8));
        out.flush();
        // Important: signal server we're done sending for THIS request
        // clientSocket.shutdownOutput() should generally be done ONCE per connection
        // for client-initiated EOF, not per request in persistence.
        // However, for testing HTTP/1.1 `curl --next` behavior, `curl` does close its output after each request part.
        // Let's assume server expects full request then full response.
        // If your server expects a client to shut down output *after each request* then keep it.
        // Otherwise, remove it for typical HTTP/1.1 persistence.
        // For `curl --next`, it seems the output is effectively "flushed and done" per part.
        // Let's remove clientSocket.shutdownOutput() here for typical HTTP/1.1 persistence.
        // If tests fail, try adding it back. Standard HTTP/1.1 typically keeps output open too.


        // 2. Read Status Line and Headers (using manual byte-by-byte line reading)
        String line;

        // Read status line
        statusLine = readLineFromStream(in);
        if (statusLine == null || statusLine.isEmpty()) {
            throw new IOException("Did not receive a status line from the server or connection closed.");
        }

        HttpStatus parsedStatus = null;
        if (statusLine.startsWith("HTTP/1.1")) { // Use String literal for HTTP version
            String[] statusParts = statusLine.split(" ", 3);
            if (statusParts.length >= 3) {
                try {
                    int code = Integer.parseInt(statusParts[1]);
                    String message = statusParts[2];
                    parsedStatus = HttpStatus.fromCodeAndMessage(code, message);
                } catch (NumberFormatException e) {
                    System.err.println(STR."Warning: Invalid status code in test client: \{statusLine}");
                }
            }
        }
        if (parsedStatus == null) {
            throw new IOException(STR."Failed to parse HttpStatus from status line: \{statusLine}");
        }

        // Read headers
        int contentLength = -1;
        while ((line = readLineFromStream(in)) != null && !line.isEmpty()) {
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String headerName = line.substring(0, colonIndex).trim();
                String headerValue = line.substring(colonIndex + 1).trim();
                headers.put(headerName.toLowerCase(), headerValue); // Store lowercase for consistent lookup
                if ("content-length".equalsIgnoreCase(headerName)) {
                    try {
                        contentLength = Integer.parseInt(headerValue);
                    } catch (NumberFormatException e) {
                        System.err.println(STR."Warning: Invalid Content-Length header in test client: \{line}");
                    }
                }
            }
        }

        // 3. Read Body
        if (contentLength > 0) {
            bodyBytes = new byte[contentLength];
            int totalBytesRead = 0;
            int bytesReadThisCall;

            while (totalBytesRead < contentLength &&
                   (bytesReadThisCall = in.read(bodyBytes, totalBytesRead, contentLength - totalBytesRead)) != -1) {
                totalBytesRead += bytesReadThisCall;
            }

            if (totalBytesRead < contentLength) {
                System.err.println(
                    STR."Warning: Expected \{contentLength} bytes, but only read \{totalBytesRead} bytes for body. Stream might have closed prematurely.");
                bodyBytes = Arrays.copyOf(bodyBytes, totalBytesRead);
            }
        } else if (contentLength == 0) {
            bodyBytes = new byte[0];
        }

        // 4. Construct HttpResponse object
        HttpResponse response = new HttpResponse(parsedStatus);
        for (Map.Entry<String, String> headerEntry : headers.entrySet()) {
            response.addHeader(headerEntry.getKey(), headerEntry.getValue());
        }
        response.setBody(bodyBytes);
        return response;
    }

    /**
     * Helper method to send an HTTP request and read its full response,
     * parsing it into an HttpResponse object. This is suitable for all tests,
     * especially those involving headers and binary content.
     *
     * @param request The full HTTP request string to send.
     * @return An HttpResponse object containing status line, headers, and raw body.
     * @throws IOException If a network I/O error occurs.
     */
    private HttpResponse sendRawHttpRequest(String request) throws IOException {
        String statusLine;
        Map<String, String> headers = new LinkedHashMap<>();
        byte[] bodyBytes = new byte[0];

        try (Socket clientSocket = new Socket("localhost", PORT);
             OutputStream out = clientSocket.getOutputStream();
             // Use BufferedInputStream for the entire reading process. This is crucial.
             BufferedInputStream in = new BufferedInputStream(clientSocket.getInputStream())) {

            // Send the request
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            clientSocket.shutdownOutput(); // Signal server we're done sending

            // Helper to read a line from a byte stream (manual line reading for robustness)
            StringBuilder lineBuilder = new StringBuilder();
            int prevChar = -1;
            int currentChar;

            // 1. Read Status Line
            while ((currentChar = in.read()) != -1) {
                if (currentChar == '\n' && prevChar == '\r') {
                    lineBuilder.setLength(lineBuilder.length() - 1); // Remove the \r
                    break;
                }
                lineBuilder.append((char) currentChar); // Append as char, as headers are text
                prevChar = currentChar;
            }
            statusLine = lineBuilder.toString();
            if (statusLine.isEmpty()) {
                throw new IOException("Did not receive a status line from the server. Connection might have closed.");
            }
            lineBuilder.setLength(0); // Clear for next line

            // Parse HttpStatus
            HttpStatus parsedStatus = null;
            if (statusLine.startsWith("HTTP/1.1")) { // Use String literal for HTTP version
                String[] statusParts = statusLine.split(" ", 3);
                if (statusParts.length >= 3) {
                    try {
                        int code = Integer.parseInt(statusParts[1]);
                        String message = statusParts[2];
                        parsedStatus = HttpStatus.fromCodeAndMessage(code, message);
                    } catch (NumberFormatException e) {
                        System.err.println(STR."Warning: Invalid status code in test client: \{statusLine}");
                    }
                }
            }
            if (parsedStatus == null) {
                throw new IOException(STR."Failed to parse HttpStatus from status line: \{statusLine}");
            }

            // 2. Read Headers
            int contentLength = -1;
            while (true) {
                prevChar = -1; // Reset for next line
                while ((currentChar = in.read()) != -1) {
                    if (currentChar == '\n' && prevChar == '\r') {
                        lineBuilder.setLength(lineBuilder.length() - 1); // Remove the \r
                        break;
                    }
                    lineBuilder.append((char) currentChar);
                    prevChar = currentChar;
                }
                String line = lineBuilder.toString();
                lineBuilder.setLength(0); // Clear for next line

                if (line.isEmpty()) { // End of headers (empty line: CRLF CRLF)
                    break;
                }

                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    String headerName = line.substring(0, colonIndex).trim();
                    String headerValue = line.substring(colonIndex + 1).trim();
                    // Store headers with original casing received from the server if your mock needs it.
                    // Or normalize to lowercase as done before: headers.put(headerName.toLowerCase(), headerValue);
                    headers.put(headerName, headerValue);
                    if ("content-length".equalsIgnoreCase(headerName)) {
                        try {
                            contentLength = Integer.parseInt(headerValue);
                        } catch (NumberFormatException e) {
                            System.err.println(STR."Warning: Invalid Content-Length header in test client: \{line}");
                        }
                    }
                } else {
                    System.err.println(STR."Warning: Malformed header line received: \{line}");
                }
            }

            // 3. Read Body (Directly as bytes from BufferedInputStream)
            if (contentLength > 0) {
                bodyBytes = new byte[contentLength];
                int totalBytesRead = 0;
                int bytesReadThisCall;

                // Loop until all expected bytes are read or end of stream is reached
                while (totalBytesRead < contentLength &&
                       (bytesReadThisCall = in.read(bodyBytes, totalBytesRead, contentLength - totalBytesRead)) != -1) {
                    totalBytesRead += bytesReadThisCall;
                }

                // If the stream ended prematurely, resize the array
                if (totalBytesRead < contentLength) {
                    System.err.println(
                        STR."Warning: Expected \{contentLength} bytes, but only read \{totalBytesRead} bytes for body. Stream might have closed prematurely.");
                    bodyBytes = Arrays.copyOf(bodyBytes, totalBytesRead);
                }
            } else if (contentLength == 0) {
                bodyBytes = new byte[0]; // Explicitly empty body
            }
            // If Content-Length is negative (not present), bodyBytes remains new byte[0]

            // 4. Construct HttpResponse object
            HttpResponse response = new HttpResponse(parsedStatus);
            for (Map.Entry<String, String> headerEntry : headers.entrySet()) {
                response.addHeader(headerEntry.getKey(), headerEntry.getValue());
            }
            response.setBody(bodyBytes); // The setBody method should handle Content-Length internally

            return response;

        } // End of try-with-resources.
    }

    /**
     * Helper to read a line from a BufferedInputStream, handling CRLF.
     * Returns null if end of stream is reached before any data for a new line.
     */
    private static String readLineFromStream(BufferedInputStream in) throws IOException {
        StringBuilder lineBuilder = new StringBuilder();
        int prevChar = -1;
        int currentChar;

        // Loop to read characters until CRLF or EOF
        while ((currentChar = in.read()) != -1) {
            if (currentChar == '\n' && prevChar == '\r') {
                lineBuilder.setLength(lineBuilder.length() - 1); // Remove the \r
                return lineBuilder.toString();
            }
            lineBuilder.append((char) currentChar); // Append as char, as headers/request line are text
            prevChar = currentChar;
        }
        // If stream ends without a full line (e.g., server closed abruptly)
        // and some data was read, return that data. Otherwise, return null (EOF at start of line).
        return !lineBuilder.isEmpty() ? lineBuilder.toString() : null;
    }

    @Nested
    @DisplayName("Stage 1 Tests: Basic Routing (Root and Unknown Paths)")
    class BasicRoutingTests {

        @Test
        @DisplayName("Should return 200 OK for the root path '/'")
        void testRootPath_Returns200OK() throws IOException {
            // String request = new TestHttpRequestBuilder("GET", "/").build();
            String request = new HttpRequest("GET", "/", "HTTP/1.1", Collections.emptyMap(), new byte[0]).toString();
            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK); // No headers/body by default
            HttpResponse actualResponse = sendRawHttpRequest(request);

            assertEquals(expectedResponse, actualResponse, "Server should return 200 OK for root path '/'");
        }

        @Test
        @DisplayName("Should return 404 Not Found for an unknown path")
        void testUnknownPath_Returns404NotFound() throws IOException {
            // String request = new TestHttpRequestBuilder("GET", "/some/random/path").build();
            String request = new HttpRequest("GET", "/some/random/path", "HTTP/1.1", Collections.emptyMap(), new byte[0]).toString();
            HttpResponse expectedResponse = new HttpResponse(HttpStatus.NOT_FOUND);
            HttpResponse actualResponse = sendRawHttpRequest(request);

            assertEquals(expectedResponse, actualResponse, "Server should return 404 Not Found for unknown paths");
        }

        @Test
        @DisplayName("Should return 404 Not Found for another unknown path like /favicon.ico")
        void testAnotherUnknownPath_Returns404NotFound() throws IOException {
            // String request = new TestHttpRequestBuilder("GET", "/favicon.ico").build();
            String request = new HttpRequest("GET", "/favicon.ico", "HTTP/1.1", Collections.emptyMap(), new byte[0]).toString();
            HttpResponse expectedResponse = new HttpResponse(HttpStatus.NOT_FOUND);
            HttpResponse actualResponse = sendRawHttpRequest(request);
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
            // String request = new TestHttpRequestBuilder("GET", STR."/echo/\{echoString}").build();
            // String request = new TestHttpRequestBuilder("GET", STR."/echo/\{echoString}").build();
            String request = new HttpRequest("GET", STR."/echo/\{echoString}", "HTTP/1.1",
                Collections.emptyMap(), echoString.getBytes(StandardCharsets.UTF_8)).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK)
                .addHeader("Content-Type", "text/plain")
                .setBody(echoString.getBytes(StandardCharsets.UTF_8)); // setBody adds Content-Length
            HttpResponse actualResponse = sendRawHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should echo a simple string correctly");
        }

        @Test
        @DisplayName("Should echo an empty string correctly")
        void testEchoEndpoint_EmptyString() throws IOException {
            String echoString = "";
            String request = new HttpRequest("GET", STR."/echo/\{echoString}", "HTTP/1.1",
                Collections.emptyMap(), echoString.getBytes(StandardCharsets.UTF_8)).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK)
                .addHeader("Content-Type", "text/plain")
                .setBody(echoString.getBytes(StandardCharsets.UTF_8));
            HttpResponse actualResponse = sendRawHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should echo an empty string correctly");
        }

        @Test
        @DisplayName("Should echo a string with spaces and special characters (URL-encoded)")
        void testEchoEndpoint_StringWithSpacesAndSpecialChars() throws IOException {
            String originalString = "hello world! This is a test.";
            String urlEncodedString = URLEncoder.encode(originalString, StandardCharsets.UTF_8);

            String request = new HttpRequest("GET", STR."/echo/\{urlEncodedString}", "HTTP/1.1",
                Collections.emptyMap(), originalString.getBytes(StandardCharsets.UTF_8)).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK)
                .addHeader("Content-Type", "text/plain")
                .setBody(originalString.getBytes(StandardCharsets.UTF_8));
            HttpResponse actualResponse = sendRawHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should echo a string with spaces and special characters correctly");
        }

        @Test
        @DisplayName("Should echo Unicode strings correctly (multi-byte characters)")
        void testEchoEndpoint_WithUnicodeCharacters() throws IOException {
            String echoString = "こんにちは世界";

            String encodedEchoString = URLEncoder.encode(echoString, StandardCharsets.UTF_8.toString());

            String request = new HttpRequest("GET", STR."/echo/\{encodedEchoString}", "HTTP/1.1",
                Collections.emptyMap(), new byte[0]
            ).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK)
                .addHeader("Content-Type", "text/plain")
                .setBody(echoString.getBytes(StandardCharsets.UTF_8));

            HttpResponse actualResponse = sendRawHttpRequest(request);
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
            String request = new HttpRequest("GET", "/user-agent", "HTTP/1.1",
                Map.of("User-Agent", userAgentValue), new byte[0]).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK)
                .addHeader("Content-Type", "text/plain")
                .setBody(userAgentValue.getBytes(StandardCharsets.UTF_8));
            HttpResponse actualResponse = sendRawHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should return the User-Agent value in the response body.");
        }

        @Test
        @DisplayName("Should handle User-Agent with spaces and special characters")
        void testUserAgentEndpoint_ComplexUserAgent() throws IOException {
            String userAgentValue = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
            String request = new HttpRequest("GET", "/user-agent", "HTTP/1.1",
                Map.of("User-Agent", userAgentValue), new byte[0]).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK)
                .addHeader("Content-Type", "text/plain")
                .setBody(userAgentValue.getBytes(StandardCharsets.UTF_8));
            HttpResponse actualResponse = sendRawHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should correctly handle complex User-Agent strings.");
        }

        @Test
        @DisplayName("Should return empty body if User-Agent header is missing")
        void testUserAgentEndpoint_MissingUserAgent() throws IOException {
            String userAgentValue = "";
            // String request = new HttpRequest("GET", "/user-agent")
            //     .build();
            String request = new HttpRequest("GET", "/user-agent", "HTTP/1.1", Map.of(), new byte[0]).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK)
                .addHeader("Content-Type", "text/plain")
                .setBody(userAgentValue.getBytes(StandardCharsets.UTF_8));
            HttpResponse actualResponse = sendRawHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should return an empty body if User-Agent header is missing.");
        }

        @Test
        @DisplayName("Should correctly parse User-Agent header regardless of casing (e.g., 'user-agent')")
        void testUserAgentEndpoint_LowercaseHeader() throws IOException {
            String userAgentValue = "lowercase-ua/2.0";
            // String request = new TestHttpRequestBuilder("GET", "/user-agent")
            //     .addHeader("user-agent", userAgentValue)
            //     .build();
            String request = new HttpRequest("GET", "/user-agent", "HTTP/1.1",
                Map.of("user-agent", userAgentValue), new byte[0]).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK)
                .addHeader("Content-Type", "text/plain")
                .setBody(userAgentValue.getBytes(StandardCharsets.UTF_8));
            HttpResponse actualResponse = sendRawHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should parse 'user-agent' header correctly.");
        }

        @Test
        @DisplayName("Should correctly parse User-Agent header regardless of casing (e.g., 'USER-AGENT')")
        void testUserAgentEndpoint_UppercaseHeader() throws IOException {
            String userAgentValue = "UPPERCASE-UA/3.0";
            // String request = new TestHttpRequestBuilder("GET", "/user-agent")
            //     .addHeader("USER-AGENT", userAgentValue)
            //     .build();
            String request = new HttpRequest("GET", "/user-agent", "HTTP/1.1",
                Map.of("USER-AGENT", userAgentValue), new byte[0]).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK)
                .addHeader("Content-Type", "text/plain")
                .setBody(userAgentValue.getBytes(StandardCharsets.UTF_8));
            HttpResponse actualResponse = sendRawHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should parse 'USER-AGENT' header correctly.");
        }
    }


    @Nested
    @DisplayName("Stage 4 Tests: Concurrency & Malformed Requests")
    class ConcurrencyAndMalformedTests {

        @Test
        @DisplayName("Should handle multiple concurrent GET / requests correctly (nc-like scenario - Structured Concurrency)")
        void testSpecificConcurrentGetRootRequests() throws Exception {
            final int numberOfConcurrentClients = 10;
            // final String request = new TestHttpRequestBuilder("GET", "/").build();
            final String request = new HttpRequest("GET", "/", "HTTP/1.1", Collections.emptyMap(), new byte[0]).toString();
            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK);

            try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
                for (int i = 0; i < numberOfConcurrentClients; i++) {
                    final int clientId = i;
                    scope.fork(() -> {
                        System.out.println(STR."Client \{clientId}: Sending GET / request.");
                        HttpResponse actualResponse = sendRawHttpRequest(request);
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
            Files.writeString(filePath, fileContent);

            // String request = new TestHttpRequestBuilder("GET", STR."/files/\{filename}").build();
            String request = new HttpRequest("GET", STR."/files/\{filename}", "HTTP/1.1", Collections.emptyMap(), new byte[0]).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK)
                .addHeader("Content-Type", "application/octet-stream")
                .setBody(fileContent.getBytes(StandardCharsets.UTF_8));
            HttpResponse actualResponse = sendRawHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should return 200 OK with correct file content.");
        }

        @Test
        @DisplayName("Should return 404 Not Found for a non-existent file")
        void testNonExistentFile() throws IOException {
            String filename = "non_existent_file.xyz";
            // String request = new TestHttpRequestBuilder("GET", STR."/files/\{filename}").build();
            String request = new HttpRequest("GET", STR."/files/\{filename}", "HTTP/1.1", Collections.emptyMap(), new byte[0]).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.NOT_FOUND);
            HttpResponse actualResponse = sendRawHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should return 404 Not Found for non-existent files.");
        }

        @Test
        @DisplayName("Should return 200 OK for an empty file")
        void testEmptyFile() throws IOException {
            String filename = "empty.txt";
            String fileContent = "";
            Path filePath = tempDirectory.resolve(filename);
            Files.writeString(filePath, fileContent);

            // String request = new TestHttpRequestBuilder("GET", STR."/files/\{filename}").build();
            String request = new HttpRequest("GET", STR."/files/\{filename}", "HTTP/1.1", Collections.emptyMap(), new byte[0]).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK)
                .addHeader("Content-Type", "application/octet-stream")
                .setBody(fileContent.getBytes(StandardCharsets.UTF_8));
            HttpResponse actualResponse = sendRawHttpRequest(request);
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
            Path filePath = tempDirectory.resolve(originalFilename);
            Files.writeString(filePath, fileContent);

            // String request = new TestHttpRequestBuilder("GET", STR."/files/\{urlEncodedFilename}").build();
            String request = new HttpRequest("GET", STR."/files/\{urlEncodedFilename}", "HTTP/1.1", Collections.emptyMap(), new byte[0]).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK)
                .addHeader("Content-Type", "application/octet-stream")
                .setBody(fileContent.getBytes(StandardCharsets.UTF_8));
            HttpResponse actualResponse = sendRawHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should handle URL-encoded filenames.");
        }

        @Test
        @Order(1)
        @DisplayName("Should create a new file with POST /files/{filename} and return 201 Created")
        void testPostFile_CreatesNewFile() throws IOException {
            String filename = "post_test_file.txt";
            String fileContent = "This is content created by a POST request.";
            Path filePath = tempDirectory.resolve(filename);

            Files.deleteIfExists(filePath);

            // String request = new TestHttpRequestBuilder("POST", STR."/files/\{filename}")
            //     .setBody(fileContent)
            //     .build();
            String request = new HttpRequest("POST", STR."/files/\{filename}", "HTTP/1.1",
                Collections.singletonMap("Content-Type", "text/plain"), fileContent.getBytes(StandardCharsets.UTF_8)).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.CREATED);
            HttpResponse actualResponse = sendRawHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should return 201 Created for file creation.");

            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            assertTrue(Files.exists(filePath), "File should be created by POST request.");
            assertTrue(Files.isRegularFile(filePath), "Created path should be a regular file.");
            String actualFileContent = Files.readString(filePath, StandardCharsets.UTF_8);
            assertEquals(fileContent, actualFileContent, "Content of the created file should match the request body.");
        }

        @Test
        @Order(2)
        @DisplayName("Should overwrite an existing file with POST /files/{filename} and return 201 Created")
        void testPostFile_OverwritesExistingFile() throws IOException {
            String filename = "overwrite_test_file.txt";
            String initialContent = "Original content.";
            String newContent = "This new content should overwrite the old one.";
            Path filePath = tempDirectory.resolve(filename);

            Files.writeString(filePath, initialContent);
            System.out.println(STR."Initial file '\{filename}' created with content: '\{initialContent}'");


            String request = new HttpRequest("POST", STR."/files/\{filename}", "HTTP/1.1",
                Map.of("Content-type", "text/plain"), newContent.getBytes(StandardCharsets.UTF_8)).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.CREATED);
            HttpResponse actualResponse = sendRawHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should return 201 Created for overwriting a file.");

            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            assertTrue(Files.exists(filePath), "Overwritten file should still exist.");
            String actualFileContent = Files.readString(filePath, StandardCharsets.UTF_8);
            assertEquals(newContent, actualFileContent, "Content of the overwritten file should match the new request body.");
        }

        @Test
        @Order(3)
        @DisplayName("Should create an empty file with POST /files/{filename} for an empty body")
        void testPostFile_EmptyBody() throws IOException {
            String filename = "empty_post_file.txt";
            Path filePath = tempDirectory.resolve(filename);

            Files.deleteIfExists(filePath);

            // String request = new TestHttpRequestBuilder("POST", STR."/files/\{filename}")
            //     .setBody("")
            //     .build();
            String request = new HttpRequest("POST", STR."/files/\{filename}", "HTTP/1.1", Collections.emptyMap(), new byte[0]).toString();

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.CREATED);
            HttpResponse actualResponse = sendRawHttpRequest(request);
            assertEquals(expectedResponse, actualResponse, "Server should return 201 Created for creating an empty file.");

            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            assertTrue(Files.exists(filePath), "Empty file should be created by POST request.");
            assertEquals(0, Files.size(filePath), "Created file should be empty.");
        }
    }

    @Nested
    @DisplayName("Stage 6 Tests: Accept-Encoding and GZIP Compression")
    class GzipCompressionTests {

        /**
         * Helper to decompress a GZIP byte array.
         */
        private String decompressGzip(byte[] compressedData) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ByteArrayInputStream bis = new ByteArrayInputStream(compressedData);
                 GZIPInputStream gis = new GZIPInputStream(bis)) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = gis.read(buffer)) > 0) {
                    out.write(buffer, 0, len);
                }
            }
            return out.toString(StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("Should return Content-Encoding: gzip and compressed body for Accept-Encoding: gzip")
        void testEchoEndpoint_GzipEncodingSingle() throws IOException {
            String originalUnencodedString = "hello world"; // This is the string we expect back
            String encodedStringForRequest = URLEncoder.encode(originalUnencodedString, StandardCharsets.UTF_8); // This is what goes in the URL
            String request = new HttpRequest("GET", STR."/echo/\{encodedStringForRequest}", "HTTP/1.1",
                Map.of("Accept-Encoding", "gzip"), new byte[0]).toString();

            HttpResponse actualResponse = sendRawHttpRequest(request);

            // Create expected response with actual compressed body (cannot pre-calculate easily)
            // We verify properties of the actual response rather than comparing to a pre-built exact byte array
            assertEquals(HttpStatus.OK, actualResponse.getStatus()); // Access status directly now
            assertEquals("text/plain", actualResponse.getHeader("Content-Type"));
            assertEquals("gzip", actualResponse.getHeader("Content-Encoding"));

            assertTrue(actualResponse.getBody().length > 0, "Compressed body should not be empty.");
            String decompressedBody = decompressGzip(actualResponse.getBody());
            assertEquals(originalUnencodedString, decompressedBody, "Decompressed body should match the original string.");

            // Content-Length header should match the actual compressed body's length
            assertEquals(String.valueOf(actualResponse.getBody().length), actualResponse.getHeader("Content-Length"));
        }

        @Test
        @DisplayName("Should return Content-Encoding: gzip for Accept-Encoding: invalid, gzip, other-invalid")
        void testEchoEndpoint_GzipEncodingMultiple() throws IOException {
            String echoString = "This is a test string for multiple encodings.";
            String encodedEchoString = URLEncoder.encode(echoString, StandardCharsets.UTF_8);

            String request = new HttpRequest("GET", STR."/echo/\{encodedEchoString}", "HTTP/1.1",
                Map.of("Accept-Encoding", "br, gzip, deflate"), new byte[0]).toString();

            HttpResponse actualResponse = sendRawHttpRequest(request);

            assertEquals(HttpStatus.OK, actualResponse.getStatus());
            assertEquals("text/plain", actualResponse.getHeader("Content-Type"));
            assertEquals("gzip", actualResponse.getHeader("Content-Encoding"));

            assertTrue(actualResponse.getBody().length > 0, "Compressed body should not be empty.");
            String decompressedBody = decompressGzip(actualResponse.getBody());
            assertEquals(echoString, decompressedBody, "Decompressed body should match the original string.");

            assertEquals(String.valueOf(actualResponse.getBody().length), actualResponse.getHeader("Content-Length"));
        }

        @Test
        @DisplayName("Should NOT return Content-Encoding: gzip for Accept-Encoding: invalid-1, invalid-2")
        void testEchoEndpoint_NoGzipEncodingUnsupported() throws IOException {
            String echoString = "no_gzip_here";
            // String request = new TestHttpRequestBuilder("GET", STR."/echo/\{echoString}")
            //     .addHeader("Accept-Encoding", "br, deflate")
            //     .build();
            String request = new HttpRequest("GET", STR."/echo/\{echoString}", "HTTP/1.1",
                Map.of("Accept-Encoding", "br, deflate"), new byte[0]).toString();

            HttpResponse actualResponse = sendRawHttpRequest(request);

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK)
                .addHeader("Content-Type", "text/plain")
                .setBody(echoString.getBytes(StandardCharsets.UTF_8));
            // Ensure no Content-Encoding header for unsupported types
            expectedResponse.removeHeader("Content-Encoding");

            assertEquals(expectedResponse, actualResponse, "Server should not apply GZIP for unsupported encodings.");
        }

        @Test
        @DisplayName("Should NOT return Content-Encoding: gzip if Accept-Encoding header is missing")
        void testEchoEndpoint_NoGzipEncodingMissingHeader() throws IOException {
            String echoString = "no_header_no_gzip";
            // String request = new TestHttpRequestBuilder("GET", STR."/echo/\{echoString}").build();
            String request = new HttpRequest("GET", STR."/echo/\{echoString}", "HTTP/1.1", Collections.emptyMap(), new byte[0]).toString();

            HttpResponse actualResponse = sendRawHttpRequest(request);

            HttpResponse expectedResponse = new HttpResponse(HttpStatus.OK)
                .addHeader("Content-Type", "text/plain")
                .setBody(echoString.getBytes(StandardCharsets.UTF_8));
            expectedResponse.removeHeader("Content-Encoding"); // Explicitly ensure no such header

            assertEquals(expectedResponse, actualResponse, "Server should not apply GZIP if Accept-Encoding header is missing.");
        }
    }

    @Nested
    @DisplayName("Stage 7 Tests: Persistent Connections")
    class PersistentConnectionTest { // Or add this method to your existing integration test class
        private Socket clientSocket;

        @BeforeEach
        void setupConnection() throws IOException {
            // Establish a single connection for the test
            clientSocket = new Socket("localhost", PORT);
            System.out.println("Test: Established new client socket for persistence test.");
        }

        @AfterEach
        void cleanupConnection() {
            // Ensure the socket is closed after each test
            if (clientSocket != null && !clientSocket.isClosed()) {
                try {
                    clientSocket.close();
                    System.out.println("Test: Closed client socket after persistence test.");
                } catch (IOException e) {
                    System.err.println("Test: Error closing socket: " + e.getMessage());
                }
            }
        }

        @Test
        void testPersistentConnection_TwoSequentialRequests() throws IOException {
            System.out.println("Test: Running testPersistentConnection_TwoSequentialRequests");

            // --- Request 1: GET /echo/banana ---
            String request1 = "GET /echo/banana HTTP/1.1\r\n" +
                              "Host: localhost:4221\r\n" +
                              "User-Agent: test-client/1.0\r\n" +
                              "\r\n"; // End of headers for request 1

            System.out.println("Test: Sending Request 1:\n" + request1.trim());
            HttpResponse response1 = sendRequestOnExistingSocket(clientSocket, request1);

            // Assertions for Response 1
            assertNotNull(response1, "Response 1 should not be null");
            assertEquals(HttpStatus.OK, response1.getStatus(), "Response 1 status should be 200 OK");
            assertEquals("text/plain", response1.getHeader("content-type"));
            assertEquals("banana", new String(response1.getBody(), StandardCharsets.UTF_8), "Response 1 body should be 'banana'");
            // Ensure Content-Length is correct
            assertEquals(String.valueOf("banana".length()), response1.getHeader("content-length"));
            // No "Connection: close" header implies persistence
            // assertFalse(response1.getHeader("connection").isPresent(), "Response 1 should not have Connection: close header");
            assertEquals(null, response1.getHeader("connection"));
            System.out.println("Test: Verified Response 1 successfully.");

            // --- Request 2: GET /user-agent with specific User-Agent ---
            String request2 = "GET /user-agent HTTP/1.1\r\n" +
                              "Host: localhost:4221\r\n" +
                              "User-Agent: blueberry/apple-blueberry\r\n" +
                              "\r\n"; // End of headers for request 2

            System.out.println("Test: Sending Request 2 (on same connection):\n" + request2.trim());
            HttpResponse response2 = sendRequestOnExistingSocket(clientSocket, request2);

            // Assertions for Response 2
            assertNotNull(response2, "Response 2 should not be null");
            assertEquals(HttpStatus.OK, response2.getStatus(), "Response 2 status should be 200 OK");
            assertEquals("text/plain", response2.getHeader("content-type"));
            assertEquals("blueberry/apple-blueberry", new String(response2.getBody(), StandardCharsets.UTF_8),
                "Response 2 body should be 'blueberry/apple-blueberry'");
            // Ensure Content-Length is correct for the second response
            assertEquals(String.valueOf("blueberry/apple-blueberry".length()), response2.getHeader("content-length"));
            assertEquals(null, response2.getHeader("connection"));
            System.out.println("Test: Verified Response 2 successfully.");

            // The @AfterEach method will close the socket here.
        }

        // You might want to add another test case for explicit Connection: close
        @Test
        void testPersistentConnection_ClientCloses() throws IOException {
            System.out.println("Test: Running testPersistentConnection_ClientCloses");

            String request = "GET /echo/test-close HTTP/1.1\r\n" +
                             "Host: localhost:4221\r\n" +
                             "Connection: close\r\n" + // Client explicitly requests closure
                             "\r\n";

            System.out.println("Test: Sending Request with Connection: close:\n" + request.trim());
            HttpResponse response = sendRequestOnExistingSocket(clientSocket, request);

            assertNotNull(response, "Response should not be null");
            assertEquals(HttpStatus.OK, response.getStatus(), "Status should be 200 OK");
            assertEquals("test-close", new String(response.getBody(), StandardCharsets.UTF_8), "Body should match 'test-close'");
            // Server should respond with Connection: close
            assertEquals("close", response.getHeader("connection"));

            // Verify that the socket is now actually closed by the server
            // This is tricky: clientSocket.isConnected() might still be true if server hasn't physically closed yet.
            // The read() call should then return -1 or throw IOException.
            try {
                int byteRead = clientSocket.getInputStream().read();
                assertEquals(-1, byteRead, "Socket input stream should be closed by server after 'Connection: close'");
            } catch (IOException e) {
                // This might happen if the socket is closed immediately.
                System.out.println("Test: Socket threw IOException as expected after server close: " + e.getMessage());
            }
            System.out.println("Test: Verified client-initiated close successfully.");
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
        executor.submit(() -> Main.main(new String[] {"--directory", tempDirectory.toAbsolutePath().toString()}));
        TimeUnit.MILLISECONDS.sleep(500); // Give server time to start
        System.out.println("Server started with --directory flag.");
    }

    @AfterAll
    static void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
            System.out.println("Server thread shut down.");
        }
    }

}