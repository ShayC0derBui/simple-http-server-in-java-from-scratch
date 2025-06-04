import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class Main {
  public static void main(String[] args) {
    System.out.println("Logs from your program will appear here!");

    try (ServerSocket serverSocket = new ServerSocket(4221)) {
      serverSocket.setReuseAddress(true); // Ensures no 'Address already in use' errors
      System.out.println("Server listening on port 4221. Waiting for connection...");

      try (Socket socket = serverSocket.accept()) {
        System.out.println("Accepted new connection from client!");
        String response = "HTTP/1.1 200 OK\r\n\r\n";

        try (OutputStream outputStream = socket.getOutputStream()) {
          outputStream.write(response.getBytes()); // Write the HTTP response bytes
          outputStream.flush();                   // Ensure all data is sent
          System.out.println("Sent HTTP 200 OK response.");
        }
      }
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }
}