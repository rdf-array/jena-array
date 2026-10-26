/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jena.rdfarray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.irix.IRIs;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.shex.ShapeMap;
import org.apache.jena.shex.ShapeMapElement;
import org.apache.jena.shex.Shex;
import org.apache.jena.shex.ShexSchema;
import org.apache.jena.shex.ShexValidator;
import org.apache.jena.shex.parser.ShexParseException;
import org.apache.jena.shex.reporting.ShexValidationReport;
import org.apache.jena.shex.sys.ShexLib;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.GraphFactory;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * rdf-array : a small HTTP server that shows the RDF Sequences data model.
 * <p>
 * Routes (GET or POST; parameters from the query string, a form body - URL-encoded or
 * multipart with file uploads - or a JSON object body):
 * <ul>
 * <li>{@code /} - the demo page (Turtle-array, SPARQL-array, ShEx-array tabs).</li>
 * <li>{@code /turtle} - {@code data} (Turtle/TriG): the parsed triples as JSON terms,
 *     and as N-Triples/N-Quads and Turtle/TriG text.</li>
 * <li>{@code /sparql} - {@code query} over {@code data} (or the preloaded data): SPARQL
 *     results JSON for SELECT, a JSON boolean for ASK, Turtle for CONSTRUCT/DESCRIBE.</li>
 * <li>the validation route named by {@code -S} (default {@code /validate}) -
 *     {@code schema}, {@code data} and either {@code queryMap} or {@code node} and
 *     {@code shape}, in the manner of the shex.js validation server; requests need only
 *     supply what the server was not started with ({@code -x -d -s -n -m}).</li>
 * </ul>
 * Errors are {@code {"type": ..., "errors": [...]}} with status 400 (bad input) or 500.
 */
public class RdfArrayServer {

    /** Command-line and preload settings. */
    public static class Config {
        public String serveUrl = "http://localhost:8099/validate";
        public String schemaFile = null;
        public String dataFile = null;
        public String shape = null;
        public String node = null;
        public String shapeMap = null;
        public int serveN = -1;
        public boolean quiet = false;
        public boolean terse = false;
    }

    public static final String USAGE = String.join("\n",
        "rdfarray -S http://host:port/validate [-x schema.shex] [-d data.ttl] [-s shape] [-n node] [-m shapeMap]",
        "         [--serve-n N] [--terse] [-Q|--quiet]",
        "",
        "Routes: /  /turtle  /sparql  and the validation route of -S (default /validate).",
        "",
        "  curl -i http://localhost:8099/turtle -F 'data=PREFIX : <http://example/>  :x :p (1 2) .'",
        "  curl -i http://localhost:8099/sparql -F 'data=PREFIX : <http://example/>  :x :p (1 2) .' \\",
        "       -F 'query=PREFIX : <http://example/> SELECT ?e { :x :p ((?e))[2] }'",
        "  curl -i http://localhost:8099/validate -F 'schema=@schema.shex' -F 'shape=http://example/S' \\",
        "       -F 'data=@data.ttl' -F 'node=http://example/x'",
        "Preload the schema and shape, then send only the data:",
        "  rdfarray -x schema.shex -s http://example/S -S http://localhost:8099/validate",
        "  curl -i http://localhost:8099/validate -F 'data=@data.ttl' -F 'node=http://example/x'");

    private final Config config;
    private final URI serveUri;
    private final String validatePath;
    private HttpServer httpServer;
    private final AtomicInteger requests = new AtomicInteger(0);
    private volatile boolean stopped = false;

    // Preloaded.
    private final String preloadedSchema;
    private final DatasetGraph preloadedData;

    public RdfArrayServer(Config config) {
        this.config = config;
        this.serveUri = URI.create(config.serveUrl);
        String path = serveUri.getPath();
        this.validatePath = (path == null || path.isEmpty() || path.equals("/")) ? "/validate" : path;
        this.preloadedSchema = (config.schemaFile == null) ? null : readFile(config.schemaFile);
        this.preloadedData = (config.dataFile == null) ? null : parseData(readFile(config.dataFile), "file:///" + config.dataFile);
    }

    private static String readFile(String file) {
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot read " + file + ": " + ex.getMessage(), ex);
        }
    }

    // ---- Lifecycle

    public void start() throws IOException {
        int port = serveUri.getPort() < 0 ? 80 : serveUri.getPort();
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/", this::handlePage);
        httpServer.createContext("/turtle", ex -> handleApi(ex, this::turtle));
        httpServer.createContext("/sparql", ex -> handleApi(ex, this::sparql));
        httpServer.createContext(validatePath, ex -> handleApi(ex, this::validate));
        httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.start();
    }

    public void stop() {
        stopped = true;
        if ( httpServer != null )
            httpServer.stop(0);
    }

    public boolean isStopped() { return stopped; }

    public int port() { return httpServer.getAddress().getPort(); }

    /** The URL of the validation route; also the base IRI for relative IRIs in requests. */
    public String baseUrl() {
        String host = serveUri.getHost() == null ? "localhost" : serveUri.getHost();
        return "http://" + host + ":" + port() + validatePath;
    }

    public String rootUrl() {
        String host = serveUri.getHost() == null ? "localhost" : serveUri.getHost();
        return "http://" + host + ":" + port() + "/";
    }

    public String validatePath() { return validatePath; }

    // ---- Request handling

    @FunctionalInterface
    private interface Api {
        Response apply(Params params) throws Exception;
    }

    private record Response(int status, String contentType, byte[] body) {
        static Response json(int status, JsonValue value) {
            return new Response(status, "application/json", jsonBytes(value));
        }
        static Response text(int status, String contentType, String text) {
            return new Response(status, contentType, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A request's parameters: first value wins when a name is repeated. */
    public static class Params {
        private final Map<String, String> values = new LinkedHashMap<>();
        public void put(String name, String value) { values.putIfAbsent(name, value); }
        public String get(String name) { return values.get(name); }
        public boolean has(String name) { String v = values.get(name); return v != null && !v.isEmpty(); }
    }

    private void handlePage(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ( ! path.equals("/") && ! path.equals("/index.html") ) {
                send(exchange, Response.json(404, error("NotFound", "No such route: " + path)));
                return;
            }
            byte[] page = readResource("index.html");
            send(exchange, new Response(200, "text/html; charset=utf-8", page));
        } finally {
            exchange.close();
        }
    }

    private void handleApi(HttpExchange exchange, Api api) throws IOException {
        try {
            if ( exchange.getRequestMethod().equals("OPTIONS") ) {
                send(exchange, new Response(204, "text/plain", new byte[0]));
                return;
            }
            Response response;
            try {
                if ( ! exchange.getRequestMethod().equals("GET") && ! exchange.getRequestMethod().equals("POST") && ! exchange.getRequestMethod().equals("HEAD") ) {
                    response = Response.json(405, error("MethodNotAllowed", "only supports GET, POST"));
                } else {
                    Params params = parseRequest(exchange);
                    response = api.apply(params);
                }
            } catch (RequestException ex) {
                response = Response.json(ex.status, error(ex.type, ex.getMessage()));
            } catch (RiotException | QueryParseException | ShexParseException | IllegalArgumentException ex) {
                response = Response.json(400, error("ParsingError", ex.getMessage()));
            } catch (Exception ex) {
                if ( ! config.quiet )
                    ex.printStackTrace();
                response = Response.json(500, error(ex.getClass().getSimpleName(), String.valueOf(ex.getMessage())));
            }
            send(exchange, response);
        } finally {
            exchange.close();
            int n = requests.incrementAndGet();
            if ( config.serveN > 0 && n >= config.serveN ) {
                log("Served %d request(s); stopping.", n);
                new Thread(this::stop).start();
            }
        }
    }

    private static class RequestException extends RuntimeException {
        final int status;
        final String type;
        RequestException(int status, String type, String message) {
            super(message);
            this.status = status;
            this.type = type;
        }
    }

    private static RequestException badRequest(String message) {
        return new RequestException(400, "ParsingError", message);
    }

    private void send(HttpExchange exchange, Response response) throws IOException {
        Headers h = exchange.getResponseHeaders();
        h.set("Content-Type", response.contentType());
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Headers", "Content-Type");
        h.set("Cache-Control", "no-cache");
        if ( response.status() == 204 || exchange.getRequestMethod().equals("HEAD") ) {
            exchange.sendResponseHeaders(response.status() == 204 ? 204 : response.status(), -1);
            return;
        }
        exchange.sendResponseHeaders(response.status(), response.body().length);
        try ( OutputStream out = exchange.getResponseBody() ) {
            out.write(response.body());
        }
    }

    // ---- Routes

    private Response turtle(Params params) {
        if ( ! params.has("data") )
            throw badRequest("No data specified.");
        String base = params.has("base") ? params.get("base") : baseUrl();
        DatasetGraph dsg = parseData(params.get("data"), base);
        JsonObject result = new JsonObject();
        JsonArray quads = new JsonArray();
        boolean named = false;
        for ( var it = dsg.find(); it.hasNext(); ) {
            Quad q = it.next();
            JsonObject jq = new JsonObject();
            if ( ! Quad.isDefaultGraph(q.getGraph()) ) {
                named = true;
                jq.put("graph", term(q.getGraph()));
            }
            jq.put("subject", term(q.getSubject()));
            jq.put("predicate", term(q.getPredicate()));
            jq.put("object", term(q.getObject()));
            quads.add(jq);
        }
        result.put("count", quads.size());
        result.put("quads", quads);
        if ( named ) {
            result.put("ntriples", RDFWriter.source(dsg).lang(Lang.NQUADS).asString());
            result.put("turtle", RDFWriter.source(dsg).lang(Lang.TRIG).asString());
        } else {
            result.put("ntriples", RDFWriter.source(dsg.getDefaultGraph()).lang(Lang.NTRIPLES).asString());
            result.put("turtle", RDFWriter.source(dsg.getDefaultGraph()).lang(Lang.TURTLE).asString());
        }
        return Response.json(200, result);
    }

    private Response sparql(Params params) {
        if ( ! params.has("query") )
            throw badRequest("No query specified.");
        DatasetGraph dsg = dataOf(params);
        Query query = QueryFactory.create(params.get("query"));
        Dataset dataset = DatasetFactory.wrap(dsg);
        try ( QueryExecution qExec = QueryExecutionFactory.create(query, dataset) ) {
            if ( query.isSelectType() ) {
                ResultSet rs = qExec.execSelect();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ResultSetFormatter.outputAsJSON(out, rs);
                return new Response(200, "application/sparql-results+json", out.toByteArray());
            }
            if ( query.isAskType() ) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ResultSetFormatter.outputAsJSON(out, qExec.execAsk());
                return new Response(200, "application/sparql-results+json", out.toByteArray());
            }
            Model model = query.isConstructType() ? qExec.execConstruct() : qExec.execDescribe();
            String ttl = RDFWriter.source(model).lang(Lang.TURTLE).asString();
            return Response.text(200, "text/turtle; charset=utf-8", ttl);
        }
    }

    private Response validate(Params params) {
        String base = baseUrl();
        String schemaText = params.has("schema") ? params.get("schema") : preloadedSchema;
        if ( schemaText == null )
            throw badRequest("No schema specified.");
        DatasetGraph dsg = dataOf(params);
        ShexSchema schema = Shex.schemaFromString(schemaText, base);

        ShapeMap shapeMap;
        String queryMap = params.has("queryMap") ? params.get("queryMap") : config.shapeMap;
        if ( queryMap != null ) {
            shapeMap = Shex.shapeMapFromString(queryMap, base);
        } else {
            String nodeStr = params.has("node") ? params.get("node") : config.node;
            if ( nodeStr == null )
                throw badRequest("No node specified.");
            String shapeStr = params.has("shape") ? params.get("shape") : config.shape;
            if ( shapeStr == null )
                throw badRequest("No shape specified.");
            Node node = resolveTerm(nodeStr, schema, base);
            Node shape = resolveTerm(shapeStr, schema, base);
            shapeMap = Shex.shapeMapFromString(NodeFmtLib.strNT(node) + "@" + NodeFmtLib.strNT(shape), base);
        }

        Graph graph = allTriples(dsg);
        ShexValidator validator = ShexValidator.getNew(List.of());
        ShexValidationReport report = validator.validate(graph, schema, shapeMap);

        if ( "boolean".equals(params.get("report")) )
            return Response.text(200, "application/json", Boolean.toString(report.conforms()));

        JsonObject result = new JsonObject();
        result.put("conforms", report.conforms());
        JsonArray results = new JsonArray();
        report.forEachReport(entry -> {
            JsonObject r = new JsonObject();
            if ( entry.nodeSelector != null )
                r.put("node", term(entry.nodeSelector));
            if ( entry.shapeExprLabel != null )
                r.put("shape", term(entry.shapeExprLabel));
            r.put("status", String.valueOf(entry.getStatus()));
            results.add(r);
        });
        result.put("results", results);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ShexLib.printReport(out, report);
        result.put("report", out.toString(StandardCharsets.UTF_8));
        return Response.json(200, result);
    }

    // ---- Helpers

    private DatasetGraph dataOf(Params params) {
        if ( params.has("data") ) {
            String base = params.has("base") ? params.get("base") : baseUrl();
            return parseData(params.get("data"), base);
        }
        if ( preloadedData != null )
            return preloadedData;
        throw badRequest("No data specified.");
    }

    /** The default graph, or every triple of every graph when the data has named graphs. */
    static Graph allTriples(DatasetGraph dsg) {
        if ( ! dsg.listGraphNodes().hasNext() )
            return dsg.getDefaultGraph();
        Graph all = GraphFactory.createDefaultGraph();
        dsg.find().forEachRemaining(q -> all.add(q.asTriple()));
        return all;
    }

    /** Parse Turtle or TriG text (TriG is a superset of Turtle). */
    static DatasetGraph parseData(String text, String base) {
        return RDFParser.fromString(text).base(base).lang(Lang.TRIG).toDatasetGraph();
    }

    /**
     * A node or shape given as a request parameter: {@code <iri>}, a prefixed name using
     * the schema's prefixes, {@code _:label}, or an IRI resolved against the base.
     */
    static Node resolveTerm(String str, ShexSchema schema, String base) {
        String s = str.trim();
        if ( s.startsWith("<") && s.endsWith(">") )
            return NodeFactory.createURI(IRIs.resolve(base, s.substring(1, s.length()-1)));
        if ( s.startsWith("_:") )
            return NodeFactory.createBlankNode(s.substring(2));
        int colon = s.indexOf(':');
        if ( colon >= 0 && schema.getPrefixMap() != null ) {
            String prefix = s.substring(0, colon);
            String ns = schema.getPrefixMap().get(prefix);
            if ( ns != null )
                return NodeFactory.createURI(ns + s.substring(colon+1));
        }
        return NodeFactory.createURI(IRIs.resolve(base, s));
    }

    /**
     * An RDF term as JSON, in the style of the SPARQL results JSON format:
     * {@code uri}, {@code bnode}, {@code literal}, {@code triple} and, new, {@code seq}
     * whose value is the array of its elements.
     */
    static JsonObject term(Node n) {
        JsonObject j = new JsonObject();
        if ( n.isURI() ) {
            j.put("type", "uri");
            j.put("value", n.getURI());
        } else if ( n.isBlank() ) {
            j.put("type", "bnode");
            j.put("value", n.getBlankNodeLabel());
        } else if ( n.isLiteral() ) {
            j.put("type", "literal");
            j.put("value", n.getLiteralLexicalForm());
            String lang = n.getLiteralLanguage();
            if ( lang != null && ! lang.isEmpty() )
                j.put("xml:lang", lang);
            else if ( n.getLiteralDatatypeURI() != null && ! n.getLiteralDatatypeURI().equals("http://www.w3.org/2001/XMLSchema#string") )
                j.put("datatype", n.getLiteralDatatypeURI());
        } else if ( n.isNodeSeq() ) {
            j.put("type", "seq");
            JsonArray elts = new JsonArray();
            for ( Node elt : n.getSeq() )
                elts.add(term(elt));
            j.put("value", elts);
        } else if ( n.isNodeTriple() ) {
            j.put("type", "triple");
            Triple t = n.getTriple();
            JsonObject jt = new JsonObject();
            jt.put("subject", term(t.getSubject()));
            jt.put("predicate", term(t.getPredicate()));
            jt.put("object", term(t.getObject()));
            j.put("value", jt);
        } else {
            j.put("type", "unknown");
            j.put("value", n.toString());
        }
        return j;
    }

    private static JsonObject error(String type, String message) {
        JsonObject j = new JsonObject();
        j.put("type", type);
        JsonArray errors = new JsonArray();
        errors.add(message == null ? "" : message);
        j.put("errors", errors);
        return j;
    }

    private static byte[] jsonBytes(JsonValue value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSON.write(out, value);
        return out.toByteArray();
    }

    private static byte[] readResource(String name) throws IOException {
        try ( InputStream in = RdfArrayServer.class.getResourceAsStream(name) ) {
            if ( in == null )
                throw new IOException("Resource not found: " + name);
            return in.readAllBytes();
        }
    }

    // ---- Request parameters: query string, URL-encoded form, multipart form, JSON object.

    static Params parseRequest(HttpExchange exchange) throws IOException {
        Params params = new Params();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if ( rawQuery != null )
            parseUrlEncoded(rawQuery, params);
        if ( exchange.getRequestMethod().equals("POST") ) {
            byte[] body;
            try ( InputStream in = exchange.getRequestBody() ) {
                body = in.readAllBytes();
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            parseBody(contentType, body, params);
        }
        return params;
    }

    static void parseBody(String contentType, byte[] body, Params params) {
        if ( body.length == 0 )
            return;
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if ( ct.startsWith("multipart/form-data") ) {
            parseMultipart(contentType, body, params);
        } else if ( ct.startsWith("application/json") ) {
            JsonObject obj = JSON.parse(new String(body, StandardCharsets.UTF_8));
            obj.forEach((k, v) -> params.put(k, v.isString() ? v.getAsString().value() : v.toString()));
        } else {
            // application/x-www-form-urlencoded, or anything else that looks like a form.
            parseUrlEncoded(new String(body, StandardCharsets.UTF_8), params);
        }
    }

    static void parseUrlEncoded(String str, Params params) {
        for ( String pair : str.split("&") ) {
            if ( pair.isEmpty() )
                continue;
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq+1);
            params.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
    }

    /** Minimal multipart/form-data parser: text fields and uploaded files, read as UTF-8. */
    static void parseMultipart(String contentType, byte[] body, Params params) {
        String boundary = null;
        for ( String part : contentType.split(";") ) {
            String p = part.trim();
            if ( p.startsWith("boundary=") ) {
                boundary = p.substring("boundary=".length()).trim();
                if ( boundary.startsWith("\"") && boundary.endsWith("\"") )
                    boundary = boundary.substring(1, boundary.length()-1);
            }
        }
        if ( boundary == null )
            throw badRequest("multipart/form-data without a boundary");
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        byte[] crlf = { '\r', '\n' };
        byte[] crlfcrlf = { '\r', '\n', '\r', '\n' };
        int pos = indexOf(body, delim, 0);
        while ( pos >= 0 ) {
            int partStart = pos + delim.length;
            if ( partStart + 1 < body.length && body[partStart] == '-' && body[partStart+1] == '-' )
                break;    // closing delimiter
            partStart = skip(body, partStart, crlf);
            int headersEnd = indexOf(body, crlfcrlf, partStart);
            if ( headersEnd < 0 )
                break;
            String headers = new String(body, partStart, headersEnd - partStart, StandardCharsets.UTF_8);
            int contentStart = headersEnd + crlfcrlf.length;
            int next = indexOf(body, delim, contentStart);
            if ( next < 0 )
                break;
            int contentEnd = next;
            if ( contentEnd >= 2 && body[contentEnd-2] == '\r' && body[contentEnd-1] == '\n' )
                contentEnd -= 2;
            String name = dispositionName(headers);
            if ( name != null )
                params.put(name, new String(body, contentStart, contentEnd - contentStart, StandardCharsets.UTF_8));
            pos = next;
        }
    }

    private static String dispositionName(String headers) {
        for ( String line : headers.split("\r\n") ) {
            if ( ! line.toLowerCase().startsWith("content-disposition:") )
                continue;
            for ( String piece : line.split(";") ) {
                String p = piece.trim();
                if ( p.startsWith("name=") ) {
                    String v = p.substring("name=".length());
                    if ( v.startsWith("\"") && v.endsWith("\"") )
                        v = v.substring(1, v.length()-1);
                    return v;
                }
            }
        }
        return null;
    }

    private static int skip(byte[] hay, int from, byte[] seq) {
        if ( from + seq.length <= hay.length && Arrays.equals(Arrays.copyOfRange(hay, from, from+seq.length), seq) )
            return from + seq.length;
        return from;
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for ( int i = Math.max(0, from) ; i <= hay.length - needle.length ; i++ ) {
            for ( int j = 0 ; j < needle.length ; j++ ) {
                if ( hay[i+j] != needle[j] )
                    continue outer;
            }
            return i;
        }
        return -1;
    }

    // ---- Command line

    private void log(String fmt, Object... args) {
        if ( ! config.quiet )
            System.out.println(String.format(fmt, args));
    }

    public static Config parseArgs(String[] args) {
        Config c = new Config();
        for ( int i = 0 ; i < args.length ; i++ ) {
            String a = args[i];
            switch (a) {
                case "-S", "--serve"   -> c.serveUrl   = args[++i];
                case "-x", "--schema"  -> c.schemaFile = args[++i];
                case "-d", "--data"    -> c.dataFile   = args[++i];
                case "-s", "--shape"   -> c.shape      = args[++i];
                case "-n", "--node"    -> c.node       = args[++i];
                case "-m", "--map"     -> c.shapeMap   = args[++i];
                case "--serve-n"       -> c.serveN     = Integer.parseInt(args[++i]);
                case "-Q", "--quiet"   -> c.quiet      = true;
                case "--terse"         -> c.terse      = true;
                case "-h", "--help"    -> { System.out.println(USAGE); System.exit(0); }
                default -> throw new IllegalArgumentException("Unknown argument: " + a + "\n" + USAGE);
            }
        }
        return c;
    }

    public static void main(String[] args) throws Exception {
        Config config;
        try {
            config = parseArgs(args);
        } catch (RuntimeException ex) {
            System.err.println(ex.getMessage());
            System.exit(2);
            return;
        }
        RdfArrayServer server = new RdfArrayServer(config);
        server.start();
        if ( config.terse ) {
            server.log("host: %s port: %d", server.serveUri.getHost(), server.port());
        } else {
            server.log("rdf-array : <%s>", server.rootUrl());
            server.log("  Turtle-array : %sturtle", server.rootUrl());
            server.log("  SPARQL-array : %ssparql", server.rootUrl());
            server.log("  ShEx-array   : %s", server.baseUrl());
            if ( server.preloadedSchema != null )
                server.log("  preloaded schema: %s", config.schemaFile);
            if ( server.preloadedData != null )
                server.log("  preloaded data:   %s", config.dataFile);
            server.log("");
            server.log("Press CTRL+C to stop...");
        }
        // Keep the main thread alive until stopped.
        while ( ! server.isStopped() )
            Thread.sleep(200);
    }
}
