import static server.HttpMethod.GET;
import static server.HttpMethod.POST;

import java.io.IOException;
import java.nio.file.Paths;

import handler.EchoHandler;
import handler.FileHandler;
import handler.PostFileHandler;
import handler.RootHandler;
import handler.UserAgentHandler;
import router.Router;
import server.HttpServer;

public class Main {
    public static void main(String[] args) {
        System.out.println("Logs from your program will appear here!");

        String filesDirectory = null;
        for (int i = 0; i < args.length; i++) {
            if ("--directory".equals(args[i]) && i + 1 < args.length) {
                filesDirectory = args[i + 1];
                System.out.println("Serving files from directory: " + filesDirectory);
                break;
            }
        }

        Router router = new Router();
        router.addRoute(GET, "/", new RootHandler());
        router.addRoute(GET, "/echo/{param}", new EchoHandler());
        router.addRoute(GET, "/user-agent", new UserAgentHandler());
        if (filesDirectory != null) {
            router.addRoute(GET, "/files/{param}", new FileHandler(Paths.get(filesDirectory)));
            router.addRoute(POST, "/files/{filename}", new PostFileHandler(Paths.get(filesDirectory)));
        }

        try {
            HttpServer server = new HttpServer(4221, router);
            server.start();
        } catch (IOException e) {
            System.err.println("Failed to start HTTP server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}