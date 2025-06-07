package request;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class HttpRequest {
    private final String method;
    private final String path;
    private final String httpVersion;
    private Map<String, String> headers;
    private final byte[] body;
    private Map<String, String> pathParameters;

    public HttpRequest(String method, String path, String httpVersion, Map<String, String> headers, byte[] body) {
        this.method = method;
        this.path = path;
        this.httpVersion = httpVersion;
        this.headers = new LinkedHashMap<>(headers);
        this.body = body != null ? body.clone() : new byte[0];
        if (this.body.length > 0) {
            this.headers.put("Content-length", String.valueOf(this.body.length));
        }
        this.pathParameters = Collections.emptyMap();
    }
    public HttpRequest(String method, String path, String httpVersion, Map<String, String> headers) {
        this.method = method;
        this.path = path;
        this.httpVersion = httpVersion;
        this.headers = Map.copyOf(headers);
        this.body = new byte[0];
        this.pathParameters = Collections.emptyMap();
    }


    public Optional<String> getPathParameter(String name) {
        return Optional.ofNullable(pathParameters.get(name));
    }

    public Map<String, String> getPathParameters() {
        return Collections.unmodifiableMap(pathParameters); // Return unmodifiable view
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
        return Collections.unmodifiableMap(headers);
    }

    public Optional<byte[]> getBody() {
        return Optional.of(body.clone());
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, path, httpVersion, headers, Arrays.hashCode(body), pathParameters);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        HttpRequest that = (HttpRequest) o;

        // Compare basic request line components
        if (!Objects.equals(method, that.method)) {
            return false;
        }
        if (!Objects.equals(path, that.path)) {
            return false;
        }
        if (!Objects.equals(httpVersion, that.httpVersion)) {
            return false;
        }

        if (headers.size() != that.headers.size()) {
            return false;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String thisKey = entry.getKey();
            String thisValue = entry.getValue();
            String thatValue = that.headers.get(thisKey);
            if (thatValue == null || !Objects.equals(thisValue, thatValue)) {
                return false;
            }
        }

        if (!Arrays.equals(body, that.body)) {
            return false;
        }

        if (pathParameters.size() != that.pathParameters.size()) {
            return false;
        }
        for (Map.Entry<String, String> entry : pathParameters.entrySet()) {
            String thisKey = entry.getKey();
            String thisValue = entry.getValue();
            String thatValue = that.pathParameters.get(thisKey);
            if (thatValue == null || !Objects.equals(thisValue, thatValue)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(" ").append(path).append(" ").append(httpVersion).append("\r\n");

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        sb.append("\r\n");

        if (body.length > 0) {
            sb.append(new String(body, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    public void setPathParameters(Map<String, String> pathParams) {
        if (pathParams == null || pathParams.isEmpty()) {
            this.pathParameters = Collections.emptyMap();
        } else {
            this.pathParameters = Map.copyOf(pathParams);
        }
    }
}