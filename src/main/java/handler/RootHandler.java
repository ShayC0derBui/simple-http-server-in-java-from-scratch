package handler;


import request.HttpRequest;
import response.HttpResponse;
import response.HttpStatus;
import router.RouteHandler;

public class RootHandler implements RouteHandler {
    @Override
    public HttpResponse handle(HttpRequest request) {
        System.out.println("Handling root path '/'");
        return new HttpResponse(HttpStatus.OK);
    }
}