package exceptions;

import java.io.IOException;

public class BadRequestException extends IOException {
    public BadRequestException(String message) {
        super(message);
    }
}
