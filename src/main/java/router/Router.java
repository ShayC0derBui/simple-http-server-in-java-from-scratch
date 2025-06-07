package router;


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
    private final Map<HttpMethod, Map<Pattern, RouteHandler>> methodRoutes;
    private final Map<HttpMethod, Map<String, String>> routePathStrings;

    public Router() {
        this.methodRoutes = new EnumMap<>(HttpMethod.class);
        for (HttpMethod method : HttpMethod.values()) {
            this.methodRoutes.put(method, new LinkedHashMap<>());
        }
        this.routePathStrings = new HashMap<>();
    }

    public void addRoute(HttpMethod method, String pathPattern, RouteHandler handler) {
        String regex = STR."^\{pathPattern.replaceAll("\\{([^}/]+)}", "([^/]*)")}$";
        Pattern compiledPattern = Pattern.compile(regex);

        methodRoutes.get(method).put(compiledPattern, handler);
        routePathStrings.put(method, Map.of(pathPattern, regex));
        System.out.println(STR."Added \{method} route pattern: '\{pathPattern}' (Regex: '\{regex}')");
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

        Map<Pattern, RouteHandler> routesToUse = methodRoutes.get(requestMethod);

        if (routesToUse == null) {
            System.out.println(STR."No handlers registered for method: \{requestMethod} for path: \{requestPath}");
            return new HttpResponse(HttpStatus.NOT_FOUND).setRequestPathForLogging(requestPath);
        }

        for (Map.Entry<Pattern, RouteHandler> entry : routesToUse.entrySet()) {
            Pattern routePattern = entry.getKey();
            RouteHandler handler = entry.getValue();

            Matcher matcher = routePattern.matcher(requestPath);
            if (matcher.matches()) {
                System.out.println(STR."Matched \{requestMethod} route: \{routePattern.pattern()} for path: \{requestPath}");

                Map<String, String> pathParams = new HashMap<>();
                String originalPathPattern = findOriginalPathPattern(requestMethod, routePattern);
                if (originalPathPattern != null) {
                    extractPathParameters(originalPathPattern, requestPath, pathParams);
                }
                request.setPathParameters(pathParams);

                return handler.handle(request);
            }
        }

        System.out.println(STR."No route found for \{requestMethod} \{requestPath}. Returning 404.");
        return new HttpResponse(HttpStatus.NOT_FOUND).setRequestPathForLogging(requestPath);
    }

    private String findOriginalPathPattern(HttpMethod requestMethod, Pattern compiledPattern) {
        Map<String, String> pathPatterns = routePathStrings.get(requestMethod);
        if (pathPatterns != null) {
            for (Map.Entry<String, String> entry : pathPatterns.entrySet()) {
                String originalPattern = entry.getKey();
                String regex = entry.getValue();
                if (compiledPattern.pattern().equals(regex)) {
                    return originalPattern;
                }
            }
        }
        return null;
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
                    pathParams.put(paramName, paramValue);
                    i++;
                }
            }
        }
    }
}