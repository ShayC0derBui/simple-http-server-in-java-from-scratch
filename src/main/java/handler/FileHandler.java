package handler;


import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import request.HttpRequest;
import response.ContentType;
import response.HttpResponse;
import response.HttpStatus;
import router.RouteHandler;

public class FileHandler implements RouteHandler {
    private final Path filesDirectory;

    public FileHandler(Path filesDirectory) {
        this.filesDirectory = filesDirectory;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        if (filesDirectory == null) {
            System.err.println("Error: File directory not initialized for FileHandler.");
            return new HttpResponse(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        String filename = request.getPath().substring("/files/".length());
        try {
            filename = URLDecoder.decode(filename, StandardCharsets.UTF_8).replace("../", "");
        } catch (IllegalArgumentException e) {
            System.err.println(STR."Malformed filename encoding: \{filename}");
            return new HttpResponse(HttpStatus.BAD_REQUEST);
        }

        Path filePath = filesDirectory.resolve(filename).normalize();

        if (!filePath.startsWith(filesDirectory)) {
            System.err.println(STR."Attempted directory traversal detected for file: \{filename}");
            return new HttpResponse(HttpStatus.FORBIDDEN);
        }

        if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
            try {
                byte[] fileBytes = Files.readAllBytes(filePath);
                System.out.println(STR."Served file: \{filename}, size: \{fileBytes.length} bytes.");
                return new HttpResponse(HttpStatus.OK)
                    .addHeader("Content-Type", ContentType.APPLICATION_OCTET_STREAM.getMimeType())
                    .setBody(fileBytes);
            } catch (IOException e) {
                System.err.println(STR."Error reading file: \{filePath} - \{e.getMessage()}");
                return new HttpResponse(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } else {
            System.out.println(STR."File not found or not a regular file: \{filename}");
            return new HttpResponse(HttpStatus.NOT_FOUND);
        }
    }
}