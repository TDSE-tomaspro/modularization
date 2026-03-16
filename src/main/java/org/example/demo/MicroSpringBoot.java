package org.example.demo;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MicroSpringBoot {

    private static final String STATIC_ROOT = "/static";
    private static final int DEFAULT_PORT = 35000;

    private final Map<String, RouteHandler> getRoutes = new LinkedHashMap<>();
    private final int port;

    public MicroSpringBoot(int port) {
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        int port = resolvePort(args);
        List<String> controllerNames = extractControllerNames(args);
        MicroSpringBoot app = new MicroSpringBoot(port);
        app.loadControllers(discoverControllerClasses(controllerNames));
        app.start();
    }

    static int resolvePort(String[] args) throws IOException {
        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                return Integer.parseInt(arg.substring("--port=".length()));
            }
        }

        Properties properties = new Properties();
        try (InputStream input = MicroSpringBoot.class.getResourceAsStream("/application.properties")) {
            if (input != null) {
                properties.load(input);
                String configuredPort = properties.getProperty("server.port");
                if (configuredPort != null && !configuredPort.isBlank()) {
                    return Integer.parseInt(configuredPort.trim());
                }
            }
        }
        return DEFAULT_PORT;
    }

    static List<String> extractControllerNames(String[] args) {
        List<String> controllerNames = new ArrayList<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                controllerNames.add(arg);
            }
        }
        return controllerNames;
    }

    static Set<Class<?>> discoverControllerClasses(List<String> controllerNames) throws Exception {
        if (!controllerNames.isEmpty()) {
            Set<Class<?>> controllers = new TreeSet<>((left, right) -> left.getName().compareTo(right.getName()));
            for (String controllerName : controllerNames) {
                Class<?> candidate = Class.forName(controllerName);
                if (candidate.isAnnotationPresent(RestController.class)) {
                    controllers.add(candidate);
                }
            }
            return controllers;
        }

        URL codeSource = MicroSpringBoot.class.getProtectionDomain().getCodeSource().getLocation();
        Path classRoot = Paths.get(codeSource.toURI());
        if (!Files.isDirectory(classRoot)) {
            return Set.of();
        }

        try (Stream<Path> classes = Files.walk(classRoot)) {
            return classes
                    .filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().contains("$"))
                    .map(path -> toClassName(classRoot, path))
                    .map(MicroSpringBoot::loadClassQuietly)
                    .filter(Objects::nonNull)
                    .filter(candidate -> candidate.isAnnotationPresent(RestController.class))
                    .collect(Collectors.toCollection(() -> new TreeSet<>((left, right) -> left.getName().compareTo(right.getName()))));
        }
    }

    private static String toClassName(Path classRoot, Path classFile) {
        String relativePath = classRoot.relativize(classFile).toString();
        return relativePath
                .substring(0, relativePath.length() - ".class".length())
                .replace('\\', '.')
                .replace('/', '.');
    }

    private static Class<?> loadClassQuietly(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            return null;
        }
    }

    void loadControllers(Set<Class<?>> controllerClasses) throws ReflectiveOperationException {
        for (Class<?> controllerClass : controllerClasses) {
            Object controller = controllerClass.getDeclaredConstructor().newInstance();
            for (Method method : controllerClass.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(GetMapping.class)) {
                    continue;
                }
                if (!String.class.equals(method.getReturnType())) {
                    throw new IllegalStateException("Only String return types are supported for " + method.getName());
                }

                GetMapping mapping = method.getAnnotation(GetMapping.class);
                String path = mapping.value();
                if (getRoutes.containsKey(path)) {
                    throw new IllegalStateException("Duplicate route registered for path " + path);
                }
                method.setAccessible(true);
                getRoutes.put(path, new RouteHandler(controller, method));
                System.out.println("Registered GET " + path + " -> " + controllerClass.getSimpleName() + "." + method.getName());
            }
        }
    }

    void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("MicroSpringBoot listening on http://localhost:" + port);
            while (true) {
                try (Socket clientSocket = serverSocket.accept()) {
                    handle(clientSocket);
                } catch (RuntimeException ex) {
                    System.err.println("Request handling error: " + ex.getMessage());
                }
            }
        }
    }

    private void handle(Socket clientSocket) throws IOException {
        BufferedInputStream input = new BufferedInputStream(clientSocket.getInputStream());
        BufferedOutputStream output = new BufferedOutputStream(clientSocket.getOutputStream());
        HttpRequest request = parseRequest(input);
        if (request == null) {
            return;
        }

        if (!"GET".equals(request.method())) {
            writeResponse(output, 405, "text/plain; charset=UTF-8", "Method Not Allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }

        RouteHandler handler = getRoutes.get(request.path());
        if (handler != null) {
            writeDynamicResponse(output, handler, request.queryParameters());
            return;
        }

        StaticResource resource = findStaticResource(request.path());
        if (resource != null) {
            writeResponse(output, 200, resource.contentType(), resource.body());
            return;
        }

        writeResponse(output, 404, "text/plain; charset=UTF-8", "Not Found".getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest parseRequest(InputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isBlank()) {
            return null;
        }

        String line;
        while ((line = reader.readLine()) != null && !line.isBlank()) {
        }

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid HTTP request line: " + requestLine);
        }

        URI uri = URI.create(parts[1]);
        String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
        return new HttpRequest(parts[0], path, parseQueryParameters(uri.getRawQuery()));
    }

    private Map<String, String> parseQueryParameters(String rawQuery) {
        Map<String, String> queryParameters = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return queryParameters;
        }

        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            queryParameters.put(key, value);
        }
        return queryParameters;
    }

    private void writeDynamicResponse(OutputStream output, RouteHandler handler, Map<String, String> queryParameters) throws IOException {
        try {
            Object[] arguments = resolveArguments(handler.method(), queryParameters);
            String body = (String) handler.method().invoke(handler.controller(), arguments);
            String contentType = isHtml(body) ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8";
            writeResponse(output, 200, contentType, body.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            writeResponse(output, 400, "text/plain; charset=UTF-8", ex.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IOException("Unable to invoke controller method", ex);
        }
    }

    private Object[] resolveArguments(Method method, Map<String, String> queryParameters) {
        Parameter[] parameters = method.getParameters();
        Object[] arguments = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
            if (requestParam == null) {
                throw new IllegalArgumentException("Missing @RequestParam annotation on parameter " + parameter.getName());
            }

            String value = queryParameters.get(requestParam.value());
            if (value == null) {
                if (!RequestParam.NO_DEFAULT_VALUE.equals(requestParam.defaultValue())) {
                    value = requestParam.defaultValue();
                } else {
                    throw new IllegalArgumentException("Missing required query parameter: " + requestParam.value());
                }
            }
            arguments[index] = convert(value, parameter.getType());
        }
        return arguments;
    }

    private Object convert(String value, Class<?> targetType) {
        if (String.class.equals(targetType)) {
            return value;
        }
        if (int.class.equals(targetType) || Integer.class.equals(targetType)) {
            return Integer.parseInt(value);
        }
        if (long.class.equals(targetType) || Long.class.equals(targetType)) {
            return Long.parseLong(value);
        }
        if (double.class.equals(targetType) || Double.class.equals(targetType)) {
            return Double.parseDouble(value);
        }
        if (boolean.class.equals(targetType) || Boolean.class.equals(targetType)) {
            return Boolean.parseBoolean(value);
        }
        throw new IllegalArgumentException("Unsupported parameter type: " + targetType.getName());
    }

    private StaticResource findStaticResource(String requestPath) throws IOException {
        String normalizedPath = "/".equals(requestPath) ? "/index.html" : requestPath;
        String decodedPath = URLDecoder.decode(normalizedPath, StandardCharsets.UTF_8);
        if (decodedPath.contains("..")) {
            return null;
        }

        String resourcePath = STATIC_ROOT + decodedPath;
        try (InputStream resource = MicroSpringBoot.class.getResourceAsStream(resourcePath)) {
            if (resource == null) {
                return null;
            }
            byte[] body = resource.readAllBytes();
            return new StaticResource(detectContentType(decodedPath), body);
        }
    }

    private String detectContentType(String path) {
        String lowercasePath = path.toLowerCase();
        if (lowercasePath.endsWith(".html") || lowercasePath.endsWith(".htm")) {
            return "text/html; charset=UTF-8";
        }
        if (lowercasePath.endsWith(".png")) {
            return "image/png";
        }
        if (lowercasePath.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (lowercasePath.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        }
        return "text/plain; charset=UTF-8";
    }

    private boolean isHtml(String body) {
        String trimmed = body == null ? "" : body.trim();
        return trimmed.startsWith("<!DOCTYPE html") || trimmed.startsWith("<html");
    }

    private void writeResponse(OutputStream output, int statusCode, String contentType, byte[] body) throws IOException {
        output.write(("HTTP/1.1 " + statusCode + " " + reasonPhrase(statusCode) + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Type: " + contentType + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Length: " + body.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        output.write(body);
        output.flush();
    }

    private String reasonPhrase(int statusCode) {
        return switch (statusCode) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            default -> "Internal Server Error";
        };
    }

    record RouteHandler(Object controller, Method method) {
    }

    record HttpRequest(String method, String path, Map<String, String> queryParameters) {
    }

    record StaticResource(String contentType, byte[] body) {
    }
}