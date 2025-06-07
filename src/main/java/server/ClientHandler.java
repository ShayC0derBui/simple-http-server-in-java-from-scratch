package server;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import exceptions.BadRequestException;
import middleware.ResponseCompressor;
import request.HttpRequest;
import request.RequestParser;
import response.ContentType;
import response.HttpResponse;
import response.HttpStatus;
import router.Router;

public class ClientHandler implements Runnable {
    private final Socket clientSocket;
    private final Router router;
    private final ResponseCompressor responseCompressor;

    public ClientHandler(Socket clientSocket, Router router, ResponseCompressor responseCompressor) {
        this.clientSocket = clientSocket;
        this.router = router;
        this.responseCompressor = responseCompressor;
    }

    @Override
    public void run() {
        try {
            clientSocket.setSoTimeout(500000); // e.g., 5 seconds of inactivity
        } catch (IOException e) {
            System.err.println("Error setting socket timeout: " + e.getMessage());
            closeSocketQuietly(clientSocket); // Close if cannot set timeout
            return;
        }

        try (BufferedInputStream bufferedInputStream = new BufferedInputStream(clientSocket.getInputStream()); // Use BufferedInputStream!
             OutputStream outputStream = clientSocket.getOutputStream()) {

            while (true) {
                HttpRequest request;
                try {
                    request = RequestParser.parse(bufferedInputStream);
                    if (request == null) {
                        System.out.println("Client gracefully closed connection or no more requests.");
                        break;
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("Client connection timed out (no new request received). Closing.");
                    break;
                } catch (BadRequestException e) {
                    System.out.println(STR."Bad request received: \{e.getMessage()}");
                    sendErrorResponse(clientSocket, HttpStatus.BAD_REQUEST, STR."400 Bad Request: \{e.getMessage()}");
                    break;
                }

                System.out.println(STR."Parsed Request: \{request.getMethod()} \{request.getPath()}");

                HttpResponse response = router.handleRequest(request);

                response = responseCompressor.compress(request, response);

                Optional<String> connectionHeader = request.getHeader("connection");
                boolean clientWantsToClose = connectionHeader.isPresent() && "close".equalsIgnoreCase(connectionHeader.get());

                if (clientWantsToClose) {
                    response.addHeader("Connection", "close");
                }

                sendResponse(outputStream, response);

                if (clientWantsToClose || !clientSocket.isConnected() || clientSocket.isInputShutdown()) {
                    System.out.println("Closing connection as requested by client or due to socket state.");
                    break;
                }
            }

        } catch (IOException e) {
            System.err.println(STR."Error handling client connection: \{e.getMessage()}");
        } finally {
            closeSocketQuietly(clientSocket);
            System.out.println("Server thread shut down for client: " + clientSocket.getInetAddress());
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
            } catch (IOException ignore) {
            }
        }
    }
}