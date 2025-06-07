package handler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import request.HttpRequest;
import response.HttpResponse;
import response.HttpStatus;
import router.RouteHandler;

public class PostFileHandler implements RouteHandler {
    private final Path filesDirectory;

    public PostFileHandler(Path filesDirectory) {
        this.filesDirectory = filesDirectory;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        if (filesDirectory == null) {
            System.err.println("Error: File directory not initialized for PostFileHandler.");
            return new HttpResponse(HttpStatus.INTERNAL_SERVER_ERROR)
                .setRequestPathForLogging(request.getPath());
        }

        String filename = request.getPathParameter("filename").orElse("");
        Path filePath = filesDirectory.resolve(filename).normalize();

        try {
            byte[] body = request.getBody().orElse(new byte[0]);
            Files.write(filePath, body);

            System.out.println(STR."Created file: \{filename}, size: \{body.length} bytes.");
            return new HttpResponse(HttpStatus.CREATED)
                .setRequestPathForLogging(request.getPath());

        } catch (IOException e) {
            System.err.println(STR."Error writing to file: \{filePath} - \{e.getMessage()}");
            return new HttpResponse(HttpStatus.INTERNAL_SERVER_ERROR)
                .setRequestPathForLogging(request.getPath());
        }
    }
}
