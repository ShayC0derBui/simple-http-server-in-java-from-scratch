package response;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpResponse {
    private static final String HTTP_VERSION = "HTTP/1.1";
    private final HttpStatus status;
    private final Map<String, String> headers;
    private byte[] body;

    private String requestPathForLogging = "unknown";

    public HttpResponse(HttpStatus status) {
        this.status = status;
        this.headers = new LinkedHashMap<>();
        this.body = null;
    }

    public HttpResponse addHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public HttpResponse setBody(byte[] body) {
        this.body = body;
        addHeader("Content-Length", String.valueOf(body != null ? body.length : 0));
        return this;
    }

    public String getRequestPathForLogging() {
        return requestPathForLogging;
    }

    public HttpResponse setRequestPathForLogging(String requestPathForLogging) {
        this.requestPathForLogging = requestPathForLogging;
        return this;
    }

    public byte[] getBytes() {
        StringBuilder headerBuilder = new StringBuilder();
        headerBuilder.append(HTTP_VERSION).append(" ").append(status.getCode()).append(" ").append(status.getMessage()).append("\r\n");

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            headerBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        headerBuilder.append("\r\n");

        byte[] headerBytes = headerBuilder.toString().getBytes(StandardCharsets.UTF_8);

        if (this.body == null || this.body.length == 0) {
            return headerBytes;
        } else {
            byte[] fullResponse = new byte[headerBytes.length + this.body.length];
            System.arraycopy(headerBytes, 0, fullResponse, 0, headerBytes.length);
            System.arraycopy(this.body, 0, fullResponse, headerBytes.length, this.body.length);
            return fullResponse;
        }
    }

}