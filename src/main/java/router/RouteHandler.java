package router;


import request.HttpRequest;
import response.HttpResponse;

public interface RouteHandler {
    HttpResponse handle(HttpRequest request);
}