import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class Main {
    public static void main(String[] args) {
        System.out.println("Logs from your program will appear here!");

        try (ServerSocket serverSocket = new ServerSocket(4221)) {
            serverSocket.setReuseAddress(true);
            System.out.println("Server listening on port 4221. Waiting for connections...");

            while (true) {
                try (Socket socket = serverSocket.accept()) {
                    System.out.println("\nAccepted new connection from client: " + socket.getInetAddress());

                    try (InputStream inputStream = socket.getInputStream();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

                        // Read the first line of the HTTP request (the request line)
                        String requestLine = reader.readLine();
                        System.out.println("Received request line: " + requestLine);

                        String requestPath = "";
                        String response;

                        // Parse the request line to extract the path
                        if (requestLine != null && !requestLine.isEmpty()) {
                            String[] parts = requestLine.split(" ");
                            // The request target (path) is the second element in the request line
                            if (parts.length > 1) {
                                requestPath = parts[1];
                            }
                        }

                        // Determine the response based on the request path
                        if ("/".equals(requestPath)) {
                            response = "HTTP/1.1 200 OK\r\n\r\n";
                            System.out.println("Path is '/', sending 200 OK.");
                        } else {
                            response = "HTTP/1.1 404 Not Found\r\n\r\n";
                            System.out.println("Path is '" + requestPath + "', sending 404 Not Found.");
                        }

                        try (OutputStream outputStream = socket.getOutputStream()) {
                            outputStream.write(response.getBytes());
                            outputStream.flush();
                            System.out.println("Response sent.");
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }
}