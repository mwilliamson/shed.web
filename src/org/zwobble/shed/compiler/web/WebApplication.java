package org.zwobble.shed.compiler.web;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

import org.zwobble.shed.compiler.CompilationResult;
import org.zwobble.shed.compiler.OptimisationLevel;
import org.zwobble.shed.compiler.ShedCompiler;
import org.zwobble.shed.compiler.SourceFileCompilationResult;
import org.zwobble.shed.compiler.errors.CompilerError;
import org.zwobble.shed.compiler.errors.CompilerErrorWithLocation;
import org.zwobble.shed.compiler.errors.CompilerErrorWithSyntaxNode;
import org.zwobble.shed.compiler.files.DelegatingFileSource;
import org.zwobble.shed.compiler.files.FileSource;
import org.zwobble.shed.compiler.files.ResourceFileSource;
import org.zwobble.shed.compiler.files.StringFileSource;
import org.zwobble.shed.compiler.parsing.NodeLocations;
import org.zwobble.shed.compiler.parsing.SourcePosition;
import org.zwobble.shed.compiler.parsing.SourceRange;
import org.zwobble.shed.compiler.tokeniser.TokenPosition;

import com.google.common.base.Joiner;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

@SuppressWarnings("restriction")
public class WebApplication {
    private static final int PORT = 8090;
    
    public static void main(String... args) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(PORT), 0);
        httpServer.createContext("/", new Handler());
        
        httpServer.start();
    }
    
    private static class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            String path = httpExchange.getRequestURI().getPath();
            System.out.println("Request for: " + path);
            if (path.equals("/compile")) {
                handleCompileRequest(httpExchange);
            } else {
                handleFileRequest(httpExchange);
            }
            System.out.println("Finished request for: " + path);
        }

        private void handleCompileRequest(HttpExchange httpExchange) throws IOException {
            try {
                ShedCompiler compiler = ShedCompiler.forBrowser(findOptimisationLevel(httpExchange));
                String source = Joiner.on("\n").join(CharStreams.readLines(new InputStreamReader(httpExchange.getRequestBody())));
                System.out.println("Source: " + source);

                FileSource fileSource = DelegatingFileSource.create(ResourceFileSource.create(), StringFileSource.create("uploaded.shed", source));
                
                CompilationResult compilationResult = compiler.compile(fileSource, "blah.main");
                
                String response = resultToJson(compilationResult);
                
                byte[] responseBody = response.getBytes();
                
                Headers responseHeaders = httpExchange.getResponseHeaders();
                responseHeaders.add("Content-Type", "application/json");
                httpExchange.sendResponseHeaders(200, responseBody.length);
                httpExchange.getResponseBody().write(responseBody);
                httpExchange.close();
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }

        private OptimisationLevel findOptimisationLevel(HttpExchange httpExchange) {
            String queryString = httpExchange.getRequestURI().getRawQuery();
            if (queryString == null) {
                return OptimisationLevel.NONE;
            }
            List<String> optimisationLevelValues = parseQueryString(queryString).get("optimisation-level");
            if (optimisationLevelValues == null) {
                return OptimisationLevel.NONE;
            }
            return OptimisationLevel.valueOf(optimisationLevelValues.get(0));
        }
        
        private String resultToJson(CompilationResult compilationResult) {
            JsonObject response = new JsonObject();
            response.addProperty("javascript", compilationResult.output());
            return response.toString();
        }

        private String resultToJson(SourceFileCompilationResult compilationResult) {
            JsonObject response = new JsonObject();
            response.add("tokens", tokensToJson(compilationResult.getTokens()));
            response.add("errors", errorsToJson(compilationResult.getErrors(), compilationResult.getNodeLocations()));
            if (compilationResult.getJavaScript() != null) {
                response.addProperty("javascript", compilationResult.getJavaScript());
            }
            return response.toString();
        }

        private JsonElement tokensToJson(Iterable<TokenPosition> tokens) {
            JsonArray json = new JsonArray();
            for (TokenPosition tokenPosition : tokens) {
                JsonObject tokenJson = new JsonObject();
                tokenJson.add("position", positionToJson(tokenPosition.getStartPosition()));
                String value = tokenPosition.getToken().getValue();
                if (value == null) {
                    value = "";
                }
                tokenJson.add("value", new JsonPrimitive(value));
                String sourceString = tokenPosition.getToken().getSourceString();
                if (sourceString == null) {
                    sourceString = "";
                }
                tokenJson.add("sourceString", new JsonPrimitive(sourceString));
                tokenJson.add("type", new JsonPrimitive(tokenPosition.getToken().getType().name().toLowerCase().replace("_", "-")));
                json.add(tokenJson);
            }
            return json;
        }

        private JsonElement errorsToJson(List<CompilerError> errors, NodeLocations nodeLocations) {
            JsonArray json = new JsonArray();
            for (CompilerError error : errors) {
                JsonObject errorJson = new JsonObject();
                errorJson.add("description", new JsonPrimitive(error.describe()));
                errorJson.add("start", positionToJson(locate(error, nodeLocations).getStart()));
                errorJson.add("end", positionToJson(locate(error, nodeLocations).getEnd()));
                json.add(errorJson);
            }
            return json;
        }
        
        private SourceRange locate(CompilerError error, NodeLocations nodeLocations) {
            if (error instanceof CompilerErrorWithLocation) {
                return ((CompilerErrorWithLocation) error).getLocation();
            } else if (error instanceof CompilerErrorWithSyntaxNode) {
                return nodeLocations.locate(((CompilerErrorWithSyntaxNode) error).getNode());
            } else {
                throw new RuntimeException("Cannot locate error");
            }
        }

        private JsonElement positionToJson(SourcePosition position) {
            JsonObject json = new JsonObject();
            json.add("lineNumber", new JsonPrimitive(position.getLineNumber()));
            json.add("characterNumber", new JsonPrimitive(position.getCharacterNumber()));
            return json;
        }

        private void handleFileRequest(HttpExchange httpExchange) throws IOException {
            FileInfo file = readFileFromPath(httpExchange.getRequestURI().getPath());
            byte[] responseBody = file.getBody();
            
            Headers responseHeaders = httpExchange.getResponseHeaders();
            responseHeaders.add("Content-Type", file.getContentType());
            httpExchange.sendResponseHeaders(200, responseBody.length);
            httpExchange.getResponseBody().write(responseBody);
            httpExchange.close();
        }
        
        private FileInfo readFileFromPath(String path) {
            try {
                if (path.substring(1).contains("/")) {
                    path = "/";
                }
                path = "web" + path;
                if (!new File(path).exists() || !new File(path).isFile()) {
                    path = "web/index.html";
                }
            
                return new FileInfo(Files.toByteArray(new File(path)), contentTypeForPath(path));
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }

        private String contentTypeForPath(String path) {
            if (path.endsWith(".js")) {
                return "application/javascript";
            }
            if (path.endsWith(".css")) {
                return "text/css";
            }
            if (path.endsWith(".gif")) {
                return "image/gif";
            }
            return "text/html";
        }
    }
    
    private static Map<String, List<String>> parseQueryString(String queryString) {
        try {
            Map<String, List<String>> params = new HashMap<String, List<String>>();
            for (String param : queryString.split("&")) {
                String pair[] = param.split("=");
                String key = URLDecoder.decode(pair[0], "UTF-8");
                String value;
                    value = URLDecoder.decode(pair[1], "UTF-8");
                if (!params.containsKey(key)) {
                    params.put(key, new ArrayList<String>());
                }
                params.get(key).add(value);
            }
            return params;
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
    
    @Data
    private static class FileInfo {
        private final byte[] body;
        private final String contentType;
    }
}

