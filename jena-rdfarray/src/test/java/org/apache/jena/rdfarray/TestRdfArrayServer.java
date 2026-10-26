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

package org.apache.jena.rdfminus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** The RDF- server: Turtle-, SPARQL- and ShEx- over HTTP, in the manner of the shex.js validation server. */
public class TestRdfMinusServer {

    private static final String PREFIXES = "PREFIX : <http://example/>\nPREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n";
    private static final String DATA   = PREFIXES + ":x :p (1 2 3) ; :q (:a (:b :c)) .\n:y :p (1 2) .\n";
    private static final String SCHEMA = PREFIXES + ":S { :p ((xsd:integer))[2] }\n";

    private static RdfMinusServer server;
    private static String root;
    private static final HttpClient client = HttpClient.newHttpClient();

    @BeforeClass public static void start() throws IOException {
        RdfMinusServer.Config c = new RdfMinusServer.Config();
        c.serveUrl = "http://localhost:0/validate";   // ephemeral port
        c.quiet = true;
        server = new RdfMinusServer(c);
        server.start();
        root = server.rootUrl();
    }

    @AfterClass public static void stop() {
        server.stop();
    }

    // ---- helpers

    private static HttpResponse<String> post(String route, Map<String, String> form) throws Exception {
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> {
            if ( body.length() > 0 ) body.append('&');
            body.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        HttpRequest req = HttpRequest.newBuilder(URI.create(root + route))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String routeAndQuery) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(root + routeAndQuery)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    // ---- page

    @Test public void index_page_is_served() throws Exception {
        HttpResponse<String> r = get("");
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        // The page writes the names with a minus sign.
        assertTrue(r.body().contains("Turtle\u2212"));
        assertTrue(r.body().contains("SPARQL\u2212"));
        assertTrue(r.body().contains("ShEx\u2212"));
        assertTrue(r.body().contains("data-tab=\"sparql\""));
    }

    @Test public void unknown_route_is_404() throws Exception {
        assertEquals(404, get("nothing-here").statusCode());
    }

    // ---- Turtle-

    @Test public void turtle_returns_sequence_terms() throws Exception {
        HttpResponse<String> r = post("turtle", Map.of("data", PREFIXES + ":x :p (1 (2 3) ()) ."));
        assertEquals(r.body(), 200, r.statusCode());
        JsonObject j = JSON.parse(r.body());
        assertEquals(1, j.get("count").getAsNumber().value().intValue());
        JsonObject o = j.get("quads").getAsArray().get(0).getAsObject().get("object").getAsObject();
        assertEquals("seq", o.get("type").getAsString().value());
        assertEquals(3, o.get("value").getAsArray().size());
        assertEquals("seq", o.get("value").getAsArray().get(1).getAsObject().get("type").getAsString().value());
        assertTrue(j.get("ntriples").getAsString().value().contains("( "));
        assertTrue(j.get("turtle").getAsString().value().contains("("));
        assertFalse(j.get("ntriples").getAsString().value().contains("rdf-syntax-ns#first"));
    }

    @Test public void turtle_syntax_error_is_400() throws Exception {
        HttpResponse<String> r = post("turtle", Map.of("data", ":x :p (1 2 ."));
        assertEquals(400, r.statusCode());
        assertEquals("ParsingError", JSON.parse(r.body()).get("type").getAsString().value());
    }

    @Test public void turtle_without_data_is_400() throws Exception {
        HttpResponse<String> r = post("turtle", Map.of());
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("No data specified."));
    }

    // ---- SPARQL-

    @Test public void sparql_select_position_pattern() throws Exception {
        HttpResponse<String> r = post("sparql", Map.of("data", DATA, "query", PREFIXES + "SELECT ?e { :x :p ((?e))[2] }"));
        assertEquals(r.body(), 200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/sparql-results+json"));
        JsonObject j = JSON.parse(r.body());
        var bindings = j.get("results").getAsObject().get("bindings").getAsArray();
        assertEquals(1, bindings.size());
        assertEquals("2", bindings.get(0).getAsObject().get("e").getAsObject().get("value").getAsString().value());
    }

    @Test public void sparql_select_returns_sequence_values() throws Exception {
        HttpResponse<String> r = post("sparql", Map.of("data", DATA, "query", PREFIXES + "SELECT ?s { :x :q ?s }"));
        assertEquals(200, r.statusCode());
        JsonObject s = JSON.parse(r.body()).get("results").getAsObject().get("bindings").getAsArray().get(0).getAsObject().get("s").getAsObject();
        assertEquals("seq", s.get("type").getAsString().value());
        assertEquals(2, s.get("value").getAsArray().size());
    }

    @Test public void sparql_get_with_query_string() throws Exception {
        HttpResponse<String> r = get("sparql?data=" + enc(DATA) + "&query=" + enc(PREFIXES + "SELECT (COUNT(*) AS ?n) { ?s :p ((?e))[*] }"));
        assertEquals(r.body(), 200, r.statusCode());
        assertTrue(r.body().contains("\"value\": \"5\""));
    }

    @Test public void sparql_ask() throws Exception {
        HttpResponse<String> r = post("sparql", Map.of("data", DATA, "query", PREFIXES + "ASK { :y :p (1 2) }"));
        assertEquals(200, r.statusCode());
        assertTrue(JSON.parse(r.body()).get("boolean").getAsBoolean().value());
    }

    @Test public void sparql_construct_returns_turtle_with_sequences() throws Exception {
        HttpResponse<String> r = post("sparql", Map.of("data", DATA, "query", PREFIXES + "CONSTRUCT { ?s :first ?f } WHERE { ?s :p ((?f))[1] }"));
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/turtle"));
        assertTrue(r.body(), r.body().contains(":first"));
        HttpResponse<String> r2 = post("sparql", Map.of("data", DATA, "query", PREFIXES + "CONSTRUCT { :z :pair (?a ?b) } WHERE { :x :p (?a ?b ?c) }"));
        assertTrue(r2.body(), r2.body().contains("( 1 2 )") || r2.body().contains("(1 2)"));
    }

    @Test public void sparql_bad_query_is_400() throws Exception {
        HttpResponse<String> r = post("sparql", Map.of("data", DATA, "query", "SELECT ?e { :x :p ((?e))[ }"));
        assertEquals(400, r.statusCode());
        assertEquals("ParsingError", JSON.parse(r.body()).get("type").getAsString().value());
    }

    @Test public void sparql_without_data_is_400() throws Exception {
        HttpResponse<String> r = post("sparql", Map.of("query", "SELECT * { ?s ?p ?o }"));
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("No data specified."));
    }

    // ---- ShEx-

    @Test public void validate_conformant_with_node_and_shape() throws Exception {
        HttpResponse<String> r = post("validate", Map.of("schema", SCHEMA, "data", DATA, "node", "http://example/y", "shape", "http://example/S"));
        assertEquals(r.body(), 200, r.statusCode());
        JsonObject j = JSON.parse(r.body());
        assertTrue(j.get("conforms").getAsBoolean().value());
        JsonObject entry = j.get("results").getAsArray().get(0).getAsObject();
        assertEquals("conformant", entry.get("status").getAsString().value());
        assertEquals("http://example/y", entry.get("node").getAsObject().get("value").getAsString().value());
        assertTrue(j.get("report").getAsString().value().contains("OK"));
    }

    @Test public void validate_nonconformant() throws Exception {
        HttpResponse<String> r = post("validate", Map.of("schema", SCHEMA, "data", DATA, "node", "<http://example/x>", "shape", "<http://example/S>"));
        assertEquals(200, r.statusCode());
        JsonObject j = JSON.parse(r.body());
        assertFalse(j.get("conforms").getAsBoolean().value());
        assertEquals("nonconformant", j.get("results").getAsArray().get(0).getAsObject().get("status").getAsString().value());
    }

    @Test public void validate_prefixed_node_and_shape_use_schema_prefixes() throws Exception {
        HttpResponse<String> r = post("validate", Map.of("schema", SCHEMA, "data", DATA, "node", ":y", "shape", ":S"));
        assertEquals(r.body(), 200, r.statusCode());
        assertTrue(JSON.parse(r.body()).get("conforms").getAsBoolean().value());
    }

    @Test public void validate_with_query_map() throws Exception {
        HttpResponse<String> r = post("validate", Map.of("schema", SCHEMA, "data", DATA, "queryMap", "<http://example/y>@<http://example/S>, <http://example/x>@<http://example/S>"));
        assertEquals(r.body(), 200, r.statusCode());
        JsonObject j = JSON.parse(r.body());
        assertFalse(j.get("conforms").getAsBoolean().value());
        assertEquals(2, j.get("results").getAsArray().size());
    }

    @Test public void validate_report_boolean() throws Exception {
        HttpResponse<String> r = post("validate", Map.of("schema", SCHEMA, "data", DATA, "node", ":y", "shape", ":S", "report", "boolean"));
        assertEquals(200, r.statusCode());
        assertEquals("true", r.body().trim());
    }

    @Test public void validate_missing_parts_are_400() throws Exception {
        HttpResponse<String> r = post("validate", Map.of("data", DATA, "node", ":y", "shape", ":S"));
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("No schema specified."));
        r = post("validate", Map.of("schema", SCHEMA, "node", ":y", "shape", ":S"));
        assertTrue(r.body().contains("No data specified."));
        r = post("validate", Map.of("schema", SCHEMA, "data", DATA, "shape", ":S"));
        assertTrue(r.body().contains("No node specified."));
        r = post("validate", Map.of("schema", SCHEMA, "data", DATA, "node", ":y"));
        assertTrue(r.body().contains("No shape specified."));
    }

    @Test public void validate_bad_schema_is_400() throws Exception {
        HttpResponse<String> r = post("validate", Map.of("schema", ":S { :p ((xsd:integer))[2 }", "data", DATA, "node", ":y", "shape", ":S"));
        assertEquals(400, r.statusCode());
        assertEquals("ParsingError", JSON.parse(r.body()).get("type").getAsString().value());
    }

    @Test public void multipart_form_with_uploaded_files() throws Exception {
        String boundary = "----rdfminus" + System.nanoTime();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"schema\"; filename=\"s.shex\"\r\nContent-Type: text/shex\r\n\r\n" + SCHEMA + "\r\n"
                    + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"data\"; filename=\"d.ttl\"\r\nContent-Type: text/turtle\r\n\r\n" + DATA + "\r\n"
                    + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"node\"\r\n\r\n:y\r\n"
                    + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"shape\"\r\n\r\n:S\r\n"
                    + "--" + boundary + "--\r\n";
        HttpRequest req = HttpRequest.newBuilder(URI.create(root + "validate"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(r.body(), 200, r.statusCode());
        assertTrue(JSON.parse(r.body()).get("conforms").getAsBoolean().value());
    }

    @Test public void json_body() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(root + "sparql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{ \"data\": " + JSON.parse("{\"d\":\"" + DATA.replace("\n", "\\n").replace("\"", "\\\"") + "\"}").get("d").toString()
                        + ", \"query\": \"PREFIX : <http://example/> SELECT ?e { :y :p ((?e))[1] }\" }")).build();
        HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(r.body(), 200, r.statusCode());
        assertTrue(r.body().contains("\"value\": \"1\""));
    }

    // ---- preloaded mode and --serve-n

    @Test public void preloaded_schema_shape_and_data_need_only_a_node() throws Exception {
        Path dir = Files.createTempDirectory("rdfminus");
        Path schemaFile = dir.resolve("s.shex");
        Path dataFile = dir.resolve("d.ttl");
        Files.writeString(schemaFile, SCHEMA);
        Files.writeString(dataFile, DATA);
        RdfMinusServer.Config c = new RdfMinusServer.Config();
        c.serveUrl = "http://localhost:0/check";
        c.quiet = true;
        c.schemaFile = schemaFile.toString();
        c.dataFile = dataFile.toString();
        c.shape = "http://example/S";
        c.serveN = 2;
        RdfMinusServer s2 = new RdfMinusServer(c);
        s2.start();
        try {
            String url = s2.rootUrl() + "check?node=" + enc("http://example/y");
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(r.body(), 200, r.statusCode());
            assertTrue(JSON.parse(r.body()).get("conforms").getAsBoolean().value());
            // Data supplied in the request overrides the preloaded data.
            String url2 = s2.rootUrl() + "check?node=" + enc("http://example/y") + "&data=" + enc(PREFIXES + ":y :p (1) .");
            r = client.send(HttpRequest.newBuilder(URI.create(url2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertFalse(JSON.parse(r.body()).get("conforms").getAsBoolean().value());
            // --serve-n 2 : the server stops after the second request.
            for ( int i = 0 ; i < 50 && ! s2.isStopped() ; i++ )
                Thread.sleep(50);
            assertTrue(s2.isStopped());
        } finally {
            s2.stop();
        }
    }

    @Test public void args_parse_like_the_shexjs_server() {
        RdfMinusServer.Config c = RdfMinusServer.parseArgs(new String[] {"-S", "http://localhost:8088/validate", "-x", "a.shex", "-s", "http://a.example/S1", "-d", "d.ttl", "-n", "x", "--serve-n", "3", "-Q", "--terse"});
        assertEquals("http://localhost:8088/validate", c.serveUrl);
        assertEquals("a.shex", c.schemaFile);
        assertEquals("http://a.example/S1", c.shape);
        assertEquals("d.ttl", c.dataFile);
        assertEquals("x", c.node);
        assertEquals(3, c.serveN);
        assertTrue(c.quiet);
        assertTrue(c.terse);
    }
}
