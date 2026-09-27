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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shex.Shex;
import org.apache.jena.shex.ShexSchema;
import org.junit.Test;

/** Update a graph in place: what the output schema holds at the root is replaced, the rest kept. */
public class TestUpdate {
    static final String OUT = "PREFIX : <http://a.example/>\nPREFIX v: <http://v.example/>\nPREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n"
            + "PREFIX Map: <http://shex.io/extensions/Map/#>\nstart = @<Out>\n"
            + "<Out> { :who xsd:string %Map:{ v:name %} ; :reading { :value xsd:string %Map:{ v:sys %} }* }\n";
    static final Node ROOT = NodeFactory.createURI("http://a.example/out");
    static final String V = "http://v.example/";
    static final String A = "http://a.example/";

    static ShexSchema out() { return Shex.schemaFromString(OUT, A); }

    static Graph turtle(String ttl) {
        return RDFParser.create().fromString("PREFIX : <http://a.example/>\n" + ttl).lang(Lang.TURTLE).base(A).toGraph();
    }

    static Bindings bindings(String name, String... readings) {
        StringBuilder sb = new StringBuilder("[{\"" + V + "name\": {\"value\": \"" + name + "\"}}, [");
        for (int i = 0; i < readings.length; i++)
            sb.append(i == 0 ? "" : ", ").append("{\"" + V + "sys\": {\"value\": \"" + readings[i] + "\"}}");
        return Bindings.loads(sb.append("]]").toString());
    }

    static Set<String> readings(Graph g) {
        Set<String> out = new HashSet<>();
        g.find(Node.ANY, NodeFactory.createURI(A + "value"), Node.ANY).forEachRemaining(t -> out.add(t.getObject().getLiteralLexicalForm()));
        return out;
    }

    @Test public void replacesWhatTheSchemaHoldsAndKeepsTheRest() {
        Graph graph = turtle(":out :unrelated \"kept\" . :elsewhere :who \"someone else\" .");
        Materializer m = new Materializer(out());
        Materializer.Update u = m.update(graph, bindings("Sue", "110", "111").tree(), ROOT, null);
        assertTrue(u.removed().isEmpty());
        assertEquals(1 + 2 + 2, u.added().size());
        assertEquals(Set.of("110", "111"), readings(graph));
        u = m.update(graph, bindings("Susan", "110", "112").tree(), ROOT, null);
        assertEquals(2, u.removed().size());           // the old name and the old value; the reading's node, minted by position, stays
        assertEquals(Set.of("110", "112"), readings(graph));
        assertTrue(graph.contains(ROOT, NodeFactory.createURI(A + "who"), NodeFactory.createLiteralString("Susan")));
        assertFalse(graph.contains(ROOT, NodeFactory.createURI(A + "who"), NodeFactory.createLiteralString("Sue")));
        assertTrue(graph.contains(ROOT, NodeFactory.createURI(A + "unrelated"), NodeFactory.createLiteralString("kept")));  // not governed: kept
        assertTrue(graph.contains(NodeFactory.createURI(A + "elsewhere"), NodeFactory.createURI(A + "who"), NodeFactory.createLiteralString("someone else")));
    }

    @Test public void anUnchangedInputChangesNothing() {
        Graph graph = ShExMap.materialize(out(), bindings("Sue", "110", "111"), ROOT, null, null);
        Set<Triple> before = new HashSet<>();
        graph.find().forEachRemaining(before::add);
        Materializer.Update u = new Materializer(out()).update(graph, bindings("Sue", "110", "111").tree(), ROOT, null);
        assertTrue(u.added().isEmpty() && u.removed().isEmpty());
        Set<Triple> after = new HashSet<>();
        graph.find().forEachRemaining(after::add);
        assertEquals(before, after);
    }

    @Test public void aRootThatDoesNotConformIsRefused() {
        Graph graph = turtle(":out :who \"Sue\" , \"Also Sue\" .");   // two names where the schema wants one
        MaterializationException e = assertThrows(MaterializationException.class,
                () -> new Materializer(out()).update(graph, bindings("Sue").tree(), ROOT, null));
        assertTrue(e.getMessage(), e.getMessage().contains("does not conform"));
    }

    @Test public void keysMergeIterationsWithTheSameValue() {
        String in = "PREFIX : <http://a.example/>\nPREFIX v: <http://v.example/>\nPREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n"
                + "PREFIX Map: <http://shex.io/extensions/Map/#>\nstart = @<In>\n"
                + "<In> { :obs { :patient xsd:string %Map:{ v:mrn %} ; :value xsd:string %Map:{ v:val %} }* }\n";
        String outKeyed = "PREFIX : <http://a.example/>\nPREFIX v: <http://v.example/>\nPREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n"
                + "PREFIX Map: <http://shex.io/extensions/Map/#>\nstart = @<Out>\n"
                + "<Out> { :patient @<Pat> * %Map:{ id(<http://a.example/patient/{v:mrn}>) %} }\n"
                + "<Pat> { :mrn xsd:string %Map:{ v:mrn %} ; :value xsd:string * %Map:{ v:val %} }\n";
        Graph data = turtle(":in :obs [ :patient \"1\" ; :value \"a\" ] , [ :patient \"1\" ; :value \"b\" ] , [ :patient \"2\" ; :value \"c\" ] .");
        Bindings b = ShExMap.bind(data, Shex.schemaFromString(in, A), NodeFactory.createURI(A + "in"), null, false);
        Graph g = ShExMap.materialize(Shex.schemaFromString(outKeyed, A), b, ROOT, null, null);
        Set<Node> patients = new HashSet<>();
        g.find(ROOT, NodeFactory.createURI(A + "patient"), Node.ANY).forEachRemaining(t -> patients.add(t.getObject()));
        assertEquals(Set.of(NodeFactory.createURI(A + "patient/1"), NodeFactory.createURI(A + "patient/2")), patients);
        assertEquals(2, g.find(NodeFactory.createURI(A + "patient/1"), NodeFactory.createURI(A + "value"), Node.ANY).toList().size());
        assertEquals(JSON.parseAny("{\"value\": \"1\"}"), Terms.toJson(NodeFactory.createLiteralString("1")));
    }
}
