# 🚀 Java HTTP Server from Scratch

This is a minimalist HTTP/1.1 server implementation built entirely in **Java 23**, demonstrating a fundamental understanding of HTTP protocols, network programming, and server-side logic. It's designed to handle basic HTTP requests, serve static files, and support essential features like content negotiation and persistent connections.

---

## ✨ Features

This server supports the following HTTP functionalities:

* **HTTP/1.1 Compliance**: Implements core HTTP/1.1 protocol rules, including request/response parsing and formatting.
* **Persistent Connections**: Supports `keep-alive` connections by default, allowing multiple requests over a single TCP connection.
* **Basic Routing**:
   * **`/` (Root)**: Serves a default welcome message.
   * **`/echo/<string>`**: Echoes the provided string back in the response body.
      * Handles **Unicode characters** in the path segment (e.g., `こんにちは世界`).
   * **`/user-agent`**: Returns the `User-Agent` header from the client's request in the response body.
   * **`/files/<filename>`**:
      * **`GET`**: Serves the content of the specified file from a local directory (e.g., `/tmp/data`).
      * **`POST`**: Accepts a request body and saves it to the specified file in the local directory.
* **HTTP Methods**: Handles `GET`, `POST`, and `HEAD` requests.
* **Content Negotiation**:
   * **`Content-Type` Header**: Correctly sets the `Content-Type` header (e.g., `text/plain`, `application/octet-stream`).
   * **`Content-Length` Header**: Dynamically sets the `Content-Length` header based on the response body size.
   * **`Accept-Encoding` Header**: Supports `gzip` compression for response bodies if requested by the client.
* **Error Handling**:
   * **`404 Not Found`**: For non-existent paths.
   * **`400 Bad Request`**: For malformed HTTP requests.

---

## 🚀 Usage Examples

Once the server is running, you can interact with it using `curl` from your terminal.

### 1. Root Endpoint

```bash
curl http://localhost:4221/
```

### 2. Echo Endpoint

**Echoing a simple string:**

```bash
curl http://localhost:4221/echo/banana
```

**Echoing a Unicode string (correctly URL-encoded):**

```bash
curl http://localhost:4221/echo/%E3%81%93%E3%82%93%E3%81%AB%E3%81%A1%E3%81%AF%E4%B8%96%E7%95%8C
# Or let curl encode it for you:
curl --path-as-is http://localhost:4221/echo/こんにちは世界
```

### 3. User-Agent Endpoint

```bash
curl http://localhost:4221/user-agent -H "User-Agent: MyCustomClient/1.0"
```

### 4. File Operations

**Create a file:**

```bash
curl -X POST http://localhost:4221/files/new_file.txt --data "This is new content."
```

**Retrieve a file:**

```bash
curl http://localhost:4221/files/my_file.txt
```

### 5. Content Compression (GZIP)

```bash
curl --compressed http://localhost:4221/echo/ThisIsALongStringForCompressionTesting -H "Accept-Encoding: gzip" -v
```

### 6. Persistent Connections

The server automatically handles persistent connections for HTTP/1.1 clients. You can test this using `curl --next`.

```bash
curl --http1.1 -v http://localhost:4221/echo/test-persistence --next http://localhost:4221/user-agent -H "User-Agent: persistence-tester/1.0"
```

---

## 📂 Project Structure

The project is organized into modular packages:

* `server/`: Contains the main server application logic, including the `HttpServer` and `ClientHandler`.
* `request/`: Classes for parsing and representing incoming HTTP requests (`HttpRequest`, `RequestParser`).
* `response/`: Classes for constructing and representing HTTP responses (`HttpResponse`, `HttpStatus`, `ContentType`).
* `router/`: Handles request routing to appropriate handlers based on the path and method.
* `middleware/`: Contains middleware components like `ResponseCompressor` for applying compression.
* `exceptions/`: Custom exceptions like `BadRequestException`.

---

## 💡 Future Enhancements

Potential improvements and features that could be added:

* Support for more HTTP methods (PUT, DELETE).
* Implementation of HTTPS/SSL for secure communication.
* Advanced routing capabilities (e.g., regex matching, route groups).
* Comprehensive logging.
* More robust error handling and custom error pages.
* Performance optimizations (e.g., thread pools, non-blocking I/O).
* Support for other `Content-Encoding` types (e.g., `deflate`, `br`).