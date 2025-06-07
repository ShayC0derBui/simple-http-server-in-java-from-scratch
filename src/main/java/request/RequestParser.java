package request;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import exceptions.BadRequestException;

public class RequestParser {

    public static HttpRequest parse(InputStream inputStream) throws IOException, BadRequestException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

        String requestLine = readRequestLine(reader);
        Map<String, String> headers = readHeaders(reader);
        byte[] body = readBody(reader, headers);

        String[] requestLineParts = requestLine.split(" ");
        if (requestLineParts.length < 3) {
            throw new BadRequestException(STR."Malformed request line: \{requestLine}");
        }

        String method = requestLineParts[0];
        String path = requestLineParts[1];
        String httpVersion = requestLineParts[2];

        return new HttpRequest(method, path, httpVersion, headers, body);
    }

    private static String readRequestLine(BufferedReader reader) throws IOException, BadRequestException {
        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isEmpty()) {
            throw new BadRequestException("Request line was null or empty.");
        }
        System.out.println(STR."Received request line: \{requestLine}");
        return requestLine;
    }

    private static Map<String, String> readHeaders(BufferedReader reader) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String headerLine;
        while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
            System.out.println(STR."Received header: \{headerLine}");
            int colonIndex = headerLine.indexOf(':');
            if (colonIndex > 0) {
                String headerName = headerLine.substring(0, colonIndex).trim().toLowerCase();
                String headerValue = headerLine.substring(colonIndex + 1).trim();
                headers.put(headerName, headerValue);
            } else {
                System.out.println(STR."Malformed header line (skipped): \{headerLine}");
            }
        }
        System.out.println(STR."Finished reading headers. Parsed headers: \{headers}");
        return headers;
    }

    private static byte[] readBody(BufferedReader reader, Map<String, String> headers) throws IOException {
        Optional<String> contentLengthHeader = Optional.ofNullable(headers.get("content-length"));
        if (contentLengthHeader.isEmpty()) {
            return null;
        }

        try {
            int contentLength = Integer.parseInt(contentLengthHeader.get());
            if (contentLength <= 0) {
                return null;
            }

            // Read the body character by character as BufferedReader works on characters
            // For binary data, it's safer to read directly from InputStream
            char[] bodyChars = new char[contentLength];
            int totalBytesRead = 0;
            int bytesRead;
            while (totalBytesRead < contentLength && (bytesRead = reader.read(bodyChars, totalBytesRead, contentLength - totalBytesRead)) != -1) {
                totalBytesRead += bytesRead;
            }

            // Convert chars to bytes, assuming UTF-8 for typical HTTP bodies
            // For truly binary bodies, the client would need to read directly from InputStream
            // For this project's scope, using BufferedReader's char-based read and then converting
            // is often sufficient for text-based bodies (like POST to /files usually has)
            return new String(bodyChars, 0, totalBytesRead).getBytes(StandardCharsets.UTF_8);

        } catch (NumberFormatException e) {
            System.err.println(STR."Invalid Content-Length header: \{contentLengthHeader.get()}");
            return null;
        }
    }
}