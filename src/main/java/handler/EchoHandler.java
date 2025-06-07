package handler;


import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import request.HttpRequest;
import response.ContentType;
import response.HttpResponse;
import response.HttpStatus;
import router.RouteHandler;

public class EchoHandler implements RouteHandler {
    @Override
    public HttpResponse handle(HttpRequest request) {
        String path = request.getPath();
        String echoString = URLDecoder.decode(path.substring("/echo/".length()), StandardCharsets.UTF_8);
        byte[] responseBodyBytes = echoString.getBytes(StandardCharsets.UTF_8);

        System.out.println(STR."Handling /echo/, echoing: '\{echoString}'");
        return new HttpResponse(HttpStatus.OK)
            .addHeader("Content-Type", ContentType.TEXT_PLAIN.getMimeType())
            .setBody(responseBodyBytes);
    }
}