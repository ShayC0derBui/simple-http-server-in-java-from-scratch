package server;


import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import middleware.ResponseCompressor;
import router.Router;

public class HttpServer {
    private final int port;
    private final Router router;
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private final ResponseCompressor responseCompressor;
    private volatile boolean running = true;

    public HttpServer(int port, Router router) {
        this.port = port;
        this.router = router;
        this.responseCompressor = new ResponseCompressor();
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        executorService = Executors.newVirtualThreadPerTaskExecutor();

        System.out.println("Server listening on port " + port + ". Waiting for connections...");

        try {
            while (running) {
                try {
                    final Socket clientSocket = serverSocket.accept();
                    System.out.println("\nAccepted new connection from client: " + clientSocket.getInetAddress());
                    executorService.submit(new ClientHandler(clientSocket, router, responseCompressor));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Error accepting client connection: " + e.getMessage());
                    }
                }
            }
        } finally {
            stop();
        }
    }

    public void stop() {
        running = false;
        if (executorService != null) {
            executorService.shutdownNow();
            System.out.println("Shutting down connection handlers...");
        }
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
                System.out.println("Server socket closed.");
            } catch (IOException e) {
                System.err.println("Error closing server socket: " + e.getMessage());
            }
        }
    }
}