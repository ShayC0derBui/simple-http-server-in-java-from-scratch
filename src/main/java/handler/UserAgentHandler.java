package handler;


import java.nio.charset.StandardCharsets;

import request.HttpRequest;
import response.ContentType;
import response.HttpResponse;
import response.HttpStatus;
import router.RouteHandler;

public class UserAgentHandler implements RouteHandler {
    @Override
    public HttpResponse handle(HttpRequest request) {
        String userAgent = request.getHeader("user-agent").orElse("");
        byte[] responseBodyBytes = userAgent.getBytes(StandardCharsets.UTF_8);

        System.out.println(STR."Handling /user-agent, User-Agent: '\{userAgent}'");
        return new HttpResponse(HttpStatus.OK)
            .addHeader("Content-Type", ContentType.TEXT_PLAIN.getMimeType())
            .setBody(responseBodyBytes);
    }
}