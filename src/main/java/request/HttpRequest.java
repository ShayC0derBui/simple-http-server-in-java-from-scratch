package request;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

public class HttpRequest {
    private final String method;
    private final String path;
    private final String httpVersion;
    private final Map<String, String> headers;
    private final byte[] body;
    private Map<String, String> pathParameters;

    public HttpRequest(String method, String path, String httpVersion, Map<String, String> headers, byte[] body) {
        this.method = method;
        this.path = path;
        this.httpVersion = httpVersion;
        this.headers = Map.copyOf(headers);
        this.body = body != null ? body.clone() : null;
        this.pathParameters = Collections.emptyMap();
    }

    public Optional<String> getPathParameter(String name) {
        return Optional.ofNullable(pathParameters.get(name));
    }

    public Map<String, String> getPathParameters() {
        return pathParameters;
    }

    public void setPathParameters(Map<String, String> pathParameters) {
        this.pathParameters = Map.copyOf(pathParameters);
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getHttpVersion() {
        return httpVersion;
    }

    public Optional<String> getHeader(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase()));
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Optional<byte[]> getBody() {
        return Optional.ofNullable(body != null ? body.clone() : null);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("HttpRequest {\n");
        sb.append("  Method: ").append(method).append("\n");
        sb.append("  Path: ").append(path).append("\n");
        sb.append("  HTTP Version: ").append(httpVersion).append("\n");
        sb.append("  Headers: ").append(headers).append("\n");
        sb.append("  Path Parameters: ").append(pathParameters).append("\n");
        if (body != null) {
            sb.append("  Body Length: ").append(body.length).append("\n");
        }
        sb.append("}");
        return sb.toString();
    }
}