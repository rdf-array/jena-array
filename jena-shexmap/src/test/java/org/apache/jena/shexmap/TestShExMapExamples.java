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

package org.apache.jena.shexmap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonString;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.irix.IRIx;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shex.Shex;
import org.apache.jena.shex.ShexSchema;
import org.junit.Test;

/** Run the ShExMap example manifests ({@code src/test/files/shexmap/shexjs}, shex.js's, and
 * {@code pyshex}, PyShEx's: ambiguity, EXTENDS and inverse cases) through the module: bind, compare
 * with the bindings shex.js recorded, materialize, compare with the expected output, and check the
 * round-trip laws. */
public class TestShExMapExamples {
    static final String TURTLE_BASE = "http://a.example/turtle/";   // the base shex.js's test runner parses data with
    static final String SCHEMA_BASE = "http://a.example/schema/";
    static final Path FILES = Path.of("src/test/files/shexmap");

    /** One manifest entry. */
    static final class Entry {
        final Path dir;
        final JsonObject json;

        Entry(Path dir, JsonObject json) { this.dir = dir; this.json = json; }

        String label() { return json.getString("schemaLabel") + " / " + json.getString("dataLabel"); }

        String text(String key) {
            if (json.hasKey(key))
                return json.getString(key);
            return read(dir.resolve(json.getString(key + "URL")));
        }

        String file(String key) { return read(dir.resolve(json.getString(key))); }

        Graph inputGraph() { return turtle(text("data")); }

        ShexSchema inputSchema() { return shexc(text("schema")); }

        ShexSchema outputSchema() { return shexc(text("outputSchema")); }

        /** (focus node, input shape label or null). */
        Node[] queryMap(Graph graph) {
            String[] ns = nodeAndShape(json.getString("queryMap"));
            Node focus;
            if (ns[0].startsWith("_:")) {
                focus = onlyRoot(graph);
            } else {
                String base = TURTLE_BASE;
                Matcher m = Pattern.compile("(?im)^\\s*BASE\\s*<([^>]*)>").matcher(text("data"));
                if (m.find())
                    base = m.group(1);
                focus = NodeFactory.createURI(IRIx.create(base).resolve(ns[0].substring(1, ns[0].length() - 1)).str());
            }
            return new Node[] { focus, ns[1] == null ? null : NodeFactory.createURI(ns[1]) };
        }

        /** (output root, output shape label or null). */
        Node[] outputShapeMap() {
            String[] ns = nodeAndShape(json.getString("outputShapeMap"));
            Node root = ns[0].startsWith("_:") ? NodeFactory.createBlankNode(ns[0].substring(2))
                    : NodeFactory.createURI(ns[0].substring(1, ns[0].length() - 1));
            return new Node[] { root, ns[1] == null ? null : NodeFactory.createURI(ns[1]) };
        }

        Graph expectedOutput(String name) {
            return turtle(read(dir.resolve(name == null ? json.getString("expectedOutputDataURL") : name)));
        }

        /** The outputs the entry allows: the expected one, or, for an ambiguous input, any of the
         * alternatives (which parse comes first depends on the data's blank-node labels). */
        List<String> expectedOutputs() {
            List<String> out = new ArrayList<>();
            if (json.hasKey("alternativeOutputDataURLs"))
                json.getArray("alternativeOutputDataURLs").forEach(v -> out.add(v.getAsString().value()));
            else
                out.add(json.getString("expectedOutputDataURL"));
            return out;
        }
    }

    static String[] nodeAndShape(String shapeMap) {
        int at = shapeMap.strip().lastIndexOf('@');
        String node = shapeMap.strip().substring(0, at);
        String shape = shapeMap.strip().substring(at + 1);
        if (shape.equals("START"))
            return new String[] { node, null };
        return new String[] { node, IRIx.create(SCHEMA_BASE).resolve(shape.substring(1, shape.length() - 1)).str() };
    }

    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static List<Entry> manifest(String sub) {
        Path dir = FILES.resolve(sub);
        JsonValue v = JSON.parseAny(read(dir.resolve("manifest.json")));
        List<Entry> out = new ArrayList<>();
        for (JsonValue e : v.getAsArray())
            out.add(new Entry(dir, e.getAsObject()));
        return out;
    }

    static List<Entry> allEntries() {
        List<Entry> all = manifest("shexjs");
        all.addAll(manifest("pyshex"));
        return all;
    }

    static ShexSchema shexc(String text) {
        return Shex.schemaFromString(text, SCHEMA_BASE);
    }

    static Graph turtle(String text) {
        return RDFParser.create().fromString(text).lang(Lang.TURTLE).base(TURTLE_BASE).toGraph();
    }

    /** The data's only root: the subject that is nobody's object. */
    static Node onlyRoot(Graph graph) {
        Set<Node> objects = new HashSet<>();
        graph.find().forEachRemaining(t -> objects.add(t.getObject()));
        Set<Node> roots = new HashSet<>();
        graph.find().forEachRemaining(t -> { if (!objects.contains(t.getSubject())) roots.add(t.getSubject()); });
        assertEquals("expected one root", 1, roots.size());
        return roots.iterator().next();
    }

    static void assertIsomorphic(String what, Graph got, Graph expected) {
        if (!got.isIsomorphicWith(expected)) {
            java.io.StringWriter g = new java.io.StringWriter();
            java.io.StringWriter e = new java.io.StringWriter();
            RDFDataMgr.write(new java.io.StringWriter() {{ }}, got, Lang.TURTLE);
            RDFDataMgr.write(g, got, Lang.NTRIPLES);
            RDFDataMgr.write(e, expected, Lang.NTRIPLES);
            fail(what + ": graphs differ\n--- got ---\n" + sorted(g.toString()) + "--- expected ---\n" + sorted(e.toString()));
        }
    }

    private static String sorted(String nt) {
        return String.join("\n", new TreeSet<>(List.of(nt.split("\n")))) + "\n";
    }

    /** The tree with blank-node values reduced to a marker (labels are the parser's). */
    static JsonValue withBnodeMarker(JsonValue v) {
        if (v.isObject()) {
            JsonObject o = new JsonObject();
            for (String k : v.getAsObject().keys()) {
                JsonValue x = v.getAsObject().get(k);
                o.put(k, x.isString() && x.getAsString().value().startsWith("_:") ? new JsonString("_:") : x);
            }
            return o;
        }
        if (v.isArray()) {
            JsonArray a = new JsonArray();
            for (JsonValue e : v.getAsArray())
                a.add(withBnodeMarker(e));
            return a;
        }
        return v;
    }

    /** {@link #withBnodeMarker}, each list's iterations sorted: ShEx matches bags, and the order a
     * neighbourhood is listed in follows the nodes' labels, which the parser chooses. */
    static JsonValue sortedLists(JsonValue v) {
        if (v.isArray()) {
            List<JsonValue> items = new ArrayList<>();
            for (JsonValue e : v.getAsArray())
                items.add(sortedLists(e));
            JsonArray a = v.getAsArray();
            boolean isList = items.stream().noneMatch(JsonValue::isArray)
                    || (items.stream().allMatch(JsonValue::isArray) && !a.get(0).isObject());
            if (isList)
                items.sort((x, y) -> canonical(x).compareTo(canonical(y)));
            JsonArray out = new JsonArray();
            items.forEach(out::add);
            return out;
        }
        return withBnodeMarker(v);
    }

    static String canonical(JsonValue v) {
        StringBuilder sb = new StringBuilder();
        BindingTree.canonicalInto(v, sb);
        return sb.toString();
    }

    /** {@link #sortedLists} without {@code @node}: the parses of an input as a comparable set. */
    static String canonicalParse(JsonValue v) {
        return canonical(sortedLists(dropNodes(v)));
    }

    static JsonValue dropNodes(JsonValue v) {
        if (v.isObject()) {
            JsonObject o = new JsonObject();
            for (String k : v.getAsObject().keys())
                if (!k.equals(BindingTree.NODE_KEY))
                    o.put(k, v.getAsObject().get(k));
            return o;
        }
        if (v.isArray()) {
            JsonArray a = new JsonArray();
            for (JsonValue e : v.getAsArray())
                a.add(dropNodes(e));
            return a;
        }
        return v;
    }

    static Bindings bindingsOf(Entry entry, Graph graph) {
        Node[] q = entry.queryMap(graph);
        try {
            return ShExMap.bind(graph, entry.inputSchema(), q[0], q[1], false);
        } catch (ShExMapException e) {
            throw new AssertionError("[" + entry.label() + "] bind: " + e.getMessage(), e);
        }
    }

    static Graph materialized(Entry entry, Bindings bindings) {
        Node[] o = entry.outputShapeMap();
        try {
            return ShExMap.materialize(entry.outputSchema(), bindings, o[0], o[1], null);
        } catch (ShExMapException e) {
            throw new AssertionError("[" + entry.label() + "] materialize: " + e.getMessage(), e);
        }
    }

    static void assertOneOfExpected(Entry entry, Graph out) {
        List<String> names = entry.expectedOutputs();
        if (names.size() == 1) {
            assertIsomorphic(entry.label(), out, entry.expectedOutput(names.get(0)));
            return;
        }
        assertTrue("[" + entry.label() + "] the output is none of " + names,
                names.stream().anyMatch(n -> out.isIsomorphicWith(entry.expectedOutput(n))));
    }

    // -- the tests ----------------------------------------------------------------------------

    @Test public void bindingsMatchShexjs() {
        // The same tree as shex.js writes, @node included, blank-node labels and list order aside.
        for (Entry entry : allEntries()) {
            if (!entry.json.hasKey("expectedBindingsURL"))
                continue;
            JsonValue expected = JSON.parseAny(entry.file("expectedBindingsURL"));
            Bindings bindings = bindingsOf(entry, entry.inputGraph());
            assertEquals("[" + entry.label() + "] differs from " + entry.json.getString("expectedBindingsURL") + ":\n" + bindings.dumps(true),
                    canonical(sortedLists(expected)), canonical(sortedLists(bindings.toJson())));
        }
    }

    // examples whose input schema has a repeated shape-valued constraint, so their trees have lists
    // of iterations, each of which must say which node it matched
    static final Set<String> HAS_ITERATIONS = Set.of("BPPatient multi-bindings / simple", "BPPatient 2 levels / simple",
            "splits / Ann-phone-mbox", "splits / Ann-mbox-phone", "inverse in / Ann's heart rate", "inverse out / Ann's chart",
            "EXTENDS / clinic");
    static final String BP = "http://shex.io/extensions/Map/#BPDAM-";

    @Test public void iterationsCarryTheNodeTheyMatched() {
        for (Entry entry : allEntries()) {
            Bindings bindings = bindingsOf(entry, entry.inputGraph());
            List<BindingTree> found = bindings.tree().nodes();
            String label = entry.label();
            if (!HAS_ITERATIONS.contains(label)) {
                assertTrue("[" + label + "] unexpected @node", found.isEmpty());
                continue;
            }
            assertFalse("[" + label + "] no @node", found.isEmpty());
            for (BindingTree d : found)
                assertTrue("[" + label + "] @node must be an IRI or a blank node", Terms.isResource(d.node()));
            if (label.equals("BPPatient 2 levels / simple")) {
                // the constraint that made the list also bound the node itself: the two must agree
                Set<Node> nodes = new HashSet<>();
                Set<Node> bound = new HashSet<>();
                for (BindingTree d : found) {
                    nodes.add(d.node());
                    Node b = d.own().get(BP + "reports");
                    bound.add(b != null ? b : d.own().get(BP + "bp"));
                }
                assertEquals(nodes, bound);
                assertEquals(6, found.size());   // two reports, four readings
            }
            if (label.equals("BPPatient multi-bindings / simple"))
                assertTrue(found.stream().allMatch(d -> d.node().equals(d.own().get(BP + "XXX"))));
            if (label.equals("inverse in / Ann's heart rate"))
                assertTrue(found.stream().allMatch(d -> d.node().isURI() && d.node().getURI().startsWith("http://")));
        }
    }

    @Test public void nodesSurviveJson() {
        for (Entry entry : allEntries()) {
            Bindings bindings = bindingsOf(entry, entry.inputGraph());
            Bindings again = Bindings.loads(bindings.dumps(false));
            assertEquals("[" + entry.label() + "]", bindings.toJson(), again.toJson());
            assertEquals(bindings.frames(), again.frames());
            assertTrue(bindings.frames().stream().noneMatch(f -> f.containsKey(BindingTree.NODE_KEY)));
        }
    }

    @Test public void bindingsSurviveARoundTripThroughTheInputSchema() {
        // bind after materialize after bind is bind: materializing bindings with the *input* schema
        // gives a graph that binds to the same bindings, i.e. a schema maps to itself.  Compared as
        // the set of parses, since an ambiguous input may parse in another order.
        for (Entry entry : allEntries()) {
            String label = entry.label();
            Graph graph = entry.inputGraph();
            Node[] q = entry.queryMap(graph);
            ShexSchema schema = entry.inputSchema();
            Set<String> before = new TreeSet<>();
            for (Bindings b : ShExMap.bindAll(graph, schema, q[0], q[1], 20))
                before.add(canonicalParse(b.toJson()));
            Node root = q[0].isBlank() ? NodeFactory.createBlankNode("root") : q[0];
            Bindings bindings = ShExMap.bind(graph, schema, q[0], q[1], false);
            Graph out;
            try {
                out = ShExMap.materialize(schema, bindings, root, q[1], null);
            } catch (ShExMapException e) {
                throw new AssertionError("[" + label + "] materialize with the input schema: " + e.getMessage(), e);
            }
            Set<String> after = new TreeSet<>();
            try {
                for (Bindings b : ShExMap.bindAll(out, schema, root, q[1], 20))
                    after.add(canonicalParse(b.toJson()));
            } catch (ShExMapException e) {
                throw new AssertionError("[" + label + "] bind the materialized graph: " + e.getMessage(), e);
            }
            assertEquals("[" + label + "]", before, after);
        }
    }

    @Test public void outputMatchesExpected() {
        for (Entry entry : allEntries()) {
            Bindings bindings = bindingsOf(entry, entry.inputGraph());
            assertOneOfExpected(entry, materialized(entry, bindings));
        }
    }

    @Test public void bindingsSurviveJsonIntoMaterialization() {
        for (Entry entry : allEntries()) {
            Bindings bindings = bindingsOf(entry, entry.inputGraph());
            assertOneOfExpected(entry, materialized(entry, Bindings.loads(bindings.dumps(true))));
        }
    }

    @Test public void materializeFromShexjsBindings() {
        // Bindings JSON written by shex.js materializes to the same graph here.
        for (Entry entry : allEntries()) {
            if (!entry.json.hasKey("expectedBindingsURL"))
                continue;
            Bindings bindings = Bindings.loads(entry.file("expectedBindingsURL"));
            assertOneOfExpected(entry, materialized(entry, bindings));
        }
    }

    @Test public void everyParseAndItsOutput() {
        // bindAll finds each distinct parse; bind reports the count; strict refuses ambiguity.
        for (Entry entry : allEntries()) {
            String label = entry.label();
            Graph graph = entry.inputGraph();
            Node[] q = entry.queryMap(graph);
            ShexSchema schema = entry.inputSchema();
            List<Bindings> alternatives = ShExMap.bindAll(graph, schema, q[0], q[1], 20);
            List<String> expected = entry.expectedOutputs();
            assertEquals("[" + label + "]", expected.size(), alternatives.size());
            boolean[] taken = new boolean[expected.size()];
            for (Bindings b : alternatives) {
                Graph out = materialized(entry, b);
                int hit = -1;
                for (int i = 0; i < expected.size() && hit < 0; i++)
                    if (!taken[i] && out.isIsomorphicWith(entry.expectedOutput(expected.get(i))))
                        hit = i;
                assertTrue("[" + label + "] a parse gives none of the remaining outputs " + expected, hit >= 0);
                taken[hit] = true;
            }
            assertEquals(expected.size(), ShExMap.bind(graph, schema, q[0], q[1], false).alternatives());
            if (expected.size() > 1) {
                AmbiguousBindingsException e = assertThrows(AmbiguousBindingsException.class,
                        () -> ShExMap.bind(graph, schema, q[0], q[1], true));
                assertEquals(expected.size(), e.getAlternatives().size());
            }
        }
    }

    @Test public void schemaPairsAnalyseCleanly() {
        for (Entry entry : allEntries()) {
            Node[] q = entry.queryMap(entry.inputGraph());
            Node[] o = entry.outputShapeMap();
            Set<String> statics = new HashSet<>();
            if (entry.json.hasKey("staticVars"))
                statics.addAll(entry.json.getObj("staticVars").keys());
            Analysis.Report report = ShExMap.analyse(entry.inputSchema(), entry.outputSchema(), q[1], o[1], statics);
            assertTrue("[" + entry.label() + "]\n" + report, report.ok());
        }
    }

    @Test public void aNonConformantFocusIsRefused() {
        Entry entry = manifest("shexjs").get(0);
        Graph graph = entry.inputGraph();
        MapValidationException e = assertThrows(MapValidationException.class,
                () -> ShExMap.bind(graph, entry.inputSchema(), NodeFactory.createURI("tag:nobody"), null, false));
        assertTrue(e.getMessage(), e.getMessage().contains("does not conform"));
    }

    @Test public void staticVariablesFillOptionalConstraints() {
        Entry entry = manifest("shexjs").get(0);   // BP / simple: :someConstProp xsd:string? %Map:{ <http://abc.example/someConstant> %}
        Bindings bindings = bindingsOf(entry, entry.inputGraph());
        Node[] o = entry.outputShapeMap();
        Materializer.Options options = new Materializer.Options()
                .staticVar("http://abc.example/someConstant", Terms.parseTerm("\"123-456\""));
        Graph out = ShExMap.materialize(entry.outputSchema(), bindings, o[0], o[1], options);
        assertTrue(out.contains(Node.ANY, NodeFactory.createURI("http://dam.example/med#someConstProp"), Node.ANY));
        assertNotNull(out);
    }

    @Test public void provenanceNamesEveryTriplesOrigin() {
        Entry entry = manifest("shexjs").get(0);
        Bindings bindings = bindingsOf(entry, entry.inputGraph());
        Node[] o = entry.outputShapeMap();
        Materializer m = new Materializer(entry.outputSchema());
        List<Triple> triples = m.materialize(bindings, o[0], o[1]);
        List<Materializer.Source> provenance = m.provenance();
        assertEquals(triples.size(), provenance.size());
        Map<Materializer.SourceKind, Integer> kinds = new TreeMap<>();
        for (Materializer.Source s : provenance)
            kinds.merge(s.kind(), 1, Integer::sum);
        assertEquals(Integer.valueOf(1), kinds.get(Materializer.SourceKind.CODE));          // the regex()-joined name
        assertEquals(Integer.valueOf(2), kinds.get(Materializer.SourceKind.STRUCTURAL));    // the two component links
        assertEquals(Integer.valueOf(4), kinds.get(Materializer.SourceKind.VARIABLE));      // values and units
        Map<String, Integer> unused = new LinkedHashMap<>();
        assertEquals(1, m.accepts().size());
        assertTrue(unused.isEmpty());
    }
}
