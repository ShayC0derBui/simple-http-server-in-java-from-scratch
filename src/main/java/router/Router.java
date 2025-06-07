package router;


import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import request.HttpRequest;
import response.HttpResponse;
import response.HttpStatus;
import server.HttpMethod;

public class Router {
    private final Map<HttpMethod, Map<String, RouteHandler>> methodRoutes;

    public Router() {
        this.methodRoutes = new EnumMap<>(HttpMethod.class);
        for (HttpMethod method : HttpMethod.values()) {
            this.methodRoutes.put(method, new LinkedHashMap<>());
        }
    }

    public void addRoute(HttpMethod method, String pathPattern, RouteHandler handler) {
        methodRoutes.get(method).put(pathPattern, handler);
        System.out.println(STR."Added \{method} route pattern: '\{pathPattern}'");
    }

    public HttpResponse handleRequest(HttpRequest request) {
        String requestPath = request.getPath();
        HttpMethod requestMethod;
        try {
            requestMethod = HttpMethod.valueOf(request.getMethod().toUpperCase());
        } catch (IllegalArgumentException e) {
            System.out.println(STR."Unsupported HTTP method received: \{request.getMethod()} for path: \{requestPath}");
            return new HttpResponse(HttpStatus.METHOD_NOT_ALLOWED).setRequestPathForLogging(requestPath);
        }

        Map<String, RouteHandler> routesToUse = methodRoutes.get(requestMethod);

        if (routesToUse == null) {
            System.out.println(STR."No handlers registered for method: \{requestMethod} for path: \{requestPath}");
            return new HttpResponse(HttpStatus.NOT_FOUND).setRequestPathForLogging(requestPath);
        }

        for (Map.Entry<String, RouteHandler> entry : routesToUse.entrySet()) {
            String originalRoutePattern = entry.getKey();
            RouteHandler handler = entry.getValue();

            String regex = STR."^\{originalRoutePattern.replaceAll("\\{([^}/]+)}", "([^/]*)")}$";
            Pattern routePattern = Pattern.compile(regex);

            Matcher matcher = routePattern.matcher(requestPath);
            if (matcher.matches()) {
                System.out.println(STR."Matched \{requestMethod} route: \{routePattern.pattern()} for path: \{requestPath}");

                Map<String, String> pathParams = new HashMap<>();
                extractPathParameters(originalRoutePattern, requestPath, pathParams);
                request.setPathParameters(pathParams);

                return handler.handle(request);
            }
        }

        System.out.println(STR."No route found for \{requestMethod} \{requestPath}. Returning 404.");
        return new HttpResponse(HttpStatus.NOT_FOUND).setRequestPathForLogging(requestPath);
    }

    private void extractPathParameters(String routePattern, String requestPath, Map<String, String> pathParams) {
        Pattern nameExtractor = Pattern.compile("\\{([^}/]+)}");
        Matcher nameMatcher = nameExtractor.matcher(routePattern);

        Pattern valueExtractor = Pattern.compile(routePattern.replaceAll("\\{([^}/]+)}", "([^/]*)"));
        Matcher valueMatcher = valueExtractor.matcher(requestPath);

        if (valueMatcher.matches()) {
            int i = 1;
            while (nameMatcher.find()) {
                String paramName = nameMatcher.group(1);
                if (i <= valueMatcher.groupCount()) {
                    String paramValue = valueMatcher.group(i);
                    String decodedParamValue = URLDecoder.decode(paramValue, StandardCharsets.UTF_8);
                    pathParams.put(paramName, decodedParamValue);
                    i++;
                }
            }
        }
    }
}