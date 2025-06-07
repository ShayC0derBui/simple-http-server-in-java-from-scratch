package request;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import exceptions.BadRequestException;

public class RequestParser {

    /**
     * Parses an HTTP request from the given InputStream.
     * This method is designed to be called repeatedly for persistent connections.
     *
     * @param inputStream The InputStream to read the request from (should be a BufferedInputStream).
     * @return An HttpRequest object, or null if the stream is empty (client closed or timeout before any data).
     * @throws IOException         If an I/O error occurs (e.g., SocketTimeoutException).
     * @throws BadRequestException If the request format is invalid.
     */
    public static HttpRequest parse(InputStream inputStream) throws IOException, BadRequestException {
        BufferedInputStream in = (inputStream instanceof BufferedInputStream) ?
            (BufferedInputStream) inputStream :
            new BufferedInputStream(inputStream);

        String requestLine = readLine(in);
        if (requestLine == null) {
            return null;
        }
        if (requestLine.isEmpty()) {
            return null;
        }
        System.out.println(STR."Received request line: \{requestLine}");

        Map<String, String> headers = readHeaders(in);
        System.out.println(STR."Finished reading headers. Parsed headers: \{headers}");

        byte[] body = readBody(in, headers);

        String[] requestLineParts = requestLine.split(" ");
        if (requestLineParts.length < 3) {
            throw new BadRequestException(STR."Malformed request line: \{requestLine}");
        }

        String method = requestLineParts[0];
        String path = requestLineParts[1];
        String httpVersion = requestLineParts[2];

        return new HttpRequest(method, path, httpVersion, headers, body);
    }

    /**
     * Helper method to read a line from a BufferedInputStream, handling CRLF.
     * Returns null if end of stream is reached before any data for a new line.
     */
    private static String readLine(BufferedInputStream in) throws IOException {
        StringBuilder lineBuilder = new StringBuilder();
        int prevChar = -1;
        int currentChar;

        // Loop to read characters until CRLF or EOF
        while ((currentChar = in.read()) != -1) {
            if (currentChar == '\n' && prevChar == '\r') {
                lineBuilder.setLength(lineBuilder.length() - 1);
                return lineBuilder.toString();
            }
            lineBuilder.append((char) currentChar);
            prevChar = currentChar;
        }
        return !lineBuilder.isEmpty() ? lineBuilder.toString() : null;
    }

    private static Map<String, String> readHeaders(BufferedInputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String headerLine;
        while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
            System.out.println(STR."Received header: \{headerLine}");
            int colonIndex = headerLine.indexOf(':');
            if (colonIndex > 0) {
                String headerName = headerLine.substring(0, colonIndex).trim().toLowerCase(); // Normalize to lowercase
                String headerValue = headerLine.substring(colonIndex + 1).trim();
                headers.put(headerName, headerValue);
            } else {
                System.out.println(STR."Malformed header line (skipped): \{headerLine}");
            }
        }
        return headers;
    }

    private static byte[] readBody(BufferedInputStream in, Map<String, String> headers) throws IOException {
        Optional<String> contentLengthHeader = Optional.ofNullable(headers.get("content-length"));
        if (contentLengthHeader.isEmpty()) {
            return new byte[0];
        }

        try {
            int contentLength = Integer.parseInt(contentLengthHeader.get());
            if (contentLength <= 0) {
                return new byte[0];
            }

            byte[] bodyBytes = new byte[contentLength];
            int totalBytesRead = 0;
            int bytesRead;

            while (totalBytesRead < contentLength &&
                   (bytesRead = in.read(bodyBytes, totalBytesRead, contentLength - totalBytesRead)) != -1) {
                totalBytesRead += bytesRead;
            }

            if (totalBytesRead < contentLength) {
                System.err.println(
                    STR."Warning: Expected \{contentLength} bytes for body, but only read \{totalBytesRead} bytes. Stream might have closed prematurely.");
                return Arrays.copyOf(bodyBytes, totalBytesRead);
            }

            return bodyBytes;

        } catch (NumberFormatException e) {
            System.err.println(STR."Invalid Content-Length header: \{contentLengthHeader.get()}");
            return new byte[0];
        }
    }
}