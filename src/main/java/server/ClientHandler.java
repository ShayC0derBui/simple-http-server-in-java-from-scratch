package server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import exceptions.BadRequestException;
import request.HttpRequest;
import request.RequestParser;
import response.ContentType;
import response.HttpResponse;
import response.HttpStatus;
import router.Router;

public class ClientHandler implements Runnable {
    private final Socket clientSocket;
    private final Router router;

    public ClientHandler(Socket clientSocket, Router router) {
        this.clientSocket = clientSocket;
        this.router = router;
    }

    @Override
    public void run() {
        try (InputStream inputStream = clientSocket.getInputStream();
             OutputStream outputStream = clientSocket.getOutputStream()) {

            HttpRequest request = RequestParser.parse(inputStream);
            System.out.println(STR."Parsed Request: \{request.getMethod()} \{request.getPath()}");

            HttpResponse response = router.handleRequest(request);
            sendResponse(outputStream, response);

        } catch (BadRequestException e) {
            System.out.println(STR."Bad request received: \{e.getMessage()}");
            sendErrorResponse(clientSocket, HttpStatus.BAD_REQUEST, STR."400 Bad Request: \{e.getMessage()}");
        } catch (IOException e) {
            System.err.println(STR."Error handling client connection: \{e.getMessage()}");
        } finally {
            closeSocketQuietly(clientSocket);
        }
    }

    private void sendResponse(OutputStream outputStream, HttpResponse response) throws IOException {
        outputStream.write(response.getBytes());
        outputStream.flush();
        System.out.println("Response sent for path: " + response.getRequestPathForLogging());
    }

    private void sendErrorResponse(Socket clientSocket, HttpStatus status, String message) {
        if (clientSocket == null || clientSocket.isClosed()) {
            return;
        }
        try (OutputStream outputStream = clientSocket.getOutputStream()) {
            HttpResponse errorResponse = new HttpResponse(status)
                .addHeader("Content-Type", ContentType.TEXT_PLAIN.getMimeType())
                .setBody(message.getBytes(StandardCharsets.UTF_8));
            sendResponse(outputStream, errorResponse);
        } catch (IOException e) {
            System.err.println("Error sending error response: " + e.getMessage());
        }
    }

    private void closeSocketQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
                System.out.println("Client socket closed.");
            } catch (IOException ignore) {
                // Ignore
            }
        }
    }
}