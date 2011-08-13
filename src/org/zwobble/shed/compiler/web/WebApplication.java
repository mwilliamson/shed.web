package org.zwobble.shed.compiler.web;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

import org.zwobble.shed.compiler.codegenerator.BrowserImportGenerator;
import org.zwobble.shed.compiler.codegenerator.BrowserModuleWrapper;
import org.zwobble.shed.compiler.codegenerator.JavaScriptGenerator;
import org.zwobble.shed.compiler.codegenerator.JavaScriptWriter;
import org.zwobble.shed.compiler.codegenerator.javascript.JavaScriptNode;
import org.zwobble.shed.compiler.parsing.CompilerError;
import org.zwobble.shed.compiler.parsing.ParseResult;
import org.zwobble.shed.compiler.parsing.Parser;
import org.zwobble.shed.compiler.parsing.SourcePosition;
import org.zwobble.shed.compiler.parsing.TokenIterator;
import org.zwobble.shed.compiler.parsing.nodes.SourceNode;
import org.zwobble.shed.compiler.tokeniser.TokenPosition;
import org.zwobble.shed.compiler.tokeniser.Tokeniser;
import org.zwobble.shed.compiler.typechecker.StaticContext;
import org.zwobble.shed.compiler.typechecker.TypeChecker;
import org.zwobble.shed.compiler.typechecker.TypeResult;

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

                String source = Joiner.on("\n").join(CharStreams.readLines(new InputStreamReader(httpExchange.getRequestBody())));
                System.out.println("Source: " + source);

                List<CompilerError> errors = new ArrayList<CompilerError>();
                
                List<TokenPosition> tokens = new Tokeniser().tokenise(source);
                ParseResult<SourceNode> parseResult = new Parser().parse(new TokenIterator(tokens));
                errors.addAll(parseResult.getErrors());
                String javaScriptOutput = null;
                
                if (parseResult.isSuccess()) {
                    TypeResult<Void> typeCheckResult = TypeChecker.typeCheck(parseResult.get(), parseResult, StaticContext.defaultContext());
                    errors.addAll(typeCheckResult.getErrors());
                    
                    if (typeCheckResult.isSuccess()) {
                        JavaScriptGenerator javaScriptGenerator =
                            new JavaScriptGenerator(new BrowserImportGenerator(), new BrowserModuleWrapper());
                        JavaScriptNode javaScript = javaScriptGenerator.generate(parseResult.get());
                        JavaScriptWriter javaScriptWriter = new JavaScriptWriter();
                        javaScriptOutput = javaScriptWriter.write(javaScript);
                    }
                }
                
                
                String response = resultToJson(tokens, errors, javaScriptOutput);
                
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

        private String resultToJson(List<TokenPosition> tokens, List<CompilerError> errors, String javaScriptOutput) {
            JsonObject response = new JsonObject();
            response.add("tokens", tokensToJson(tokens));
            response.add("errors", errorsToJson(errors));
            if (javaScriptOutput != null) {
                response.addProperty("javascript", javaScriptOutput);
            }
            return response.toString();
        }

        private JsonElement tokensToJson(List<TokenPosition> tokens) {
            JsonArray json = new JsonArray();
            for (TokenPosition tokenPosition : tokens) {
                JsonObject tokenJson = new JsonObject();
                tokenJson.add("position", positionToJson(tokenPosition.getPosition()));
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

        private JsonElement errorsToJson(List<CompilerError> errors) {
            JsonArray json = new JsonArray();
            for (CompilerError error : errors) {
                JsonObject errorJson = new JsonObject();
                errorJson.add("description", new JsonPrimitive(error.getDescription()));
                errorJson.add("start", positionToJson(error.getLocation().getStart()));
                errorJson.add("end", positionToJson(error.getLocation().getEnd()));
                json.add(errorJson);
            }
            return json;
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
            if (path.substring(1).contains("/")) {
                path = "/";
            }
            path = "web" + path;
            if (!new File(path).exists() || !new File(path).isFile()) {
                path = "web/index.html";
            }
            try {
                
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
    
    @Data
    private static class FileInfo {
        private final byte[] body;
        private final String contentType;
    }
}

