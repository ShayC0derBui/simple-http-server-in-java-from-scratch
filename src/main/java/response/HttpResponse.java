package response;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class HttpResponse {
    public static final String HTTP_VERSION = "HTTP/1.1";
    private final HttpStatus status;
    private final Map<String, String> headers;
    private byte[] body;

    private String requestPathForLogging = "unknown";

    public HttpResponse(HttpStatus status) {
        this.status = status;
        this.headers = new LinkedHashMap<>();
        this.body = new byte[0];
    }

    public HttpResponse addHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public HttpResponse removeHeader(String name) {
        String keyToRemove = null;
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                keyToRemove = key;
                break;
            }
        }
        if (keyToRemove != null) {
            headers.remove(keyToRemove);
        }
        return this;
    }

    public String getRequestPathForLogging() {
        return requestPathForLogging;
    }

    public HttpResponse setRequestPathForLogging(String requestPathForLogging) {
        this.requestPathForLogging = requestPathForLogging;
        return this;
    }

    public byte[] getBody() {
        return body;
    }

    public HttpResponse setBody(byte[] body) {
        this.body = body != null ? body : new byte[0];
        if (body.length > 0) {
            addHeader("Content-Length", String.valueOf(body.length));
        }
        return this;
    }

    public String getHeader(String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }


    public byte[] getBytes() {
        StringBuilder headerBuilder = new StringBuilder();
        headerBuilder.append(HTTP_VERSION).append(" ").append(status.getCode()).append(" ").append(status.getMessage()).append("\r\n");

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            headerBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        headerBuilder.append("\r\n");

        byte[] headerBytes = headerBuilder.toString().getBytes(StandardCharsets.UTF_8);

        if (this.body.length == 0) {
            return headerBytes;
        } else {
            byte[] fullResponse = new byte[headerBytes.length + this.body.length];
            System.arraycopy(headerBytes, 0, fullResponse, 0, headerBytes.length);
            System.arraycopy(this.body, 0, fullResponse, headerBytes.length, this.body.length);
            return fullResponse;
        }
    }

    public HttpStatus getStatus() {
        return status;
    }

    @Override
    public int hashCode() {
        Map<String, String> headersLower = new HashMap<>();
        for (Map.Entry<String, String> entry : this.headers.entrySet()) {
            headersLower.put(entry.getKey().toLowerCase(), entry.getValue());
        }
        return Objects.hash(status, headersLower, Arrays.hashCode(body));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        HttpResponse that = (HttpResponse) o;

        if (!Objects.equals(status, that.status)) {
            return false;
        }

        if (headers.size() != that.headers.size()) {
            return false;
        }

        Map<String, String> thisHeadersLower = new HashMap<>();
        for (Map.Entry<String, String> entry : this.headers.entrySet()) {
            thisHeadersLower.put(entry.getKey().toLowerCase(), entry.getValue());
        }

        Map<String, String> thatHeadersLower = new HashMap<>();
        for (Map.Entry<String, String> entry : that.headers.entrySet()) {
            thatHeadersLower.put(entry.getKey().toLowerCase(), entry.getValue());
        }

        if (!Objects.equals(thisHeadersLower, thatHeadersLower)) {
            return false;
        }

        return Arrays.equals(body, that.body);
    }

    @Override
    public String toString() {
        return new String(getBytes(), StandardCharsets.UTF_8);
    }
}