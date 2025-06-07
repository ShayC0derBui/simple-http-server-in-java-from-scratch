package response;

public enum HttpStatus {
    OK(200, "OK"),
    NOT_FOUND(404, "Not Found"),
    BAD_REQUEST(400, "Bad Request"),
    INTERNAL_SERVER_ERROR(500, "Internal Server Error"),
    FORBIDDEN(403, "Forbidden"),
    METHOD_NOT_ALLOWED(405, "Method not allowed"),
    CREATED(201, "Created");

    private final int code;
    private final String message;

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public static HttpStatus fromCodeAndMessage(int code, String message) {
        for (HttpStatus status : HttpStatus.values()) {
            if (status.getCode() == code && status.getMessage().equalsIgnoreCase(message)) {
                return status;
            }
        }
        return null;
    }

    HttpStatus(int code, String message) {
        this.code = code;
        this.message = message;
    }
}