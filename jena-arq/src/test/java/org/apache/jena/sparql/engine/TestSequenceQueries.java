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

package org.apache.jena.sparql.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.exec.QueryExec;
import org.apache.jena.sparql.exec.RowSet;
import org.apache.jena.sparql.sse.SSE;
import org.apache.jena.update.UpdateAction;
import org.junit.Test;

/** RDF sequences in SPARQL: sequence patterns, position patterns {@code ((x))[i]}, functions. */
public class TestSequenceQueries {

    private static final String PREFIX = "PREFIX : <http://example/>\n";
    private static final String DATA = PREFIX
            + ":x :p (1 2 3) .\n"
            + ":y :p (:a (:b :c)) .\n"
            + ":z :p () .\n"
            + ":w :p 'not a sequence' .\n";

    private static Graph data() {
        return RDFParser.fromString(DATA).lang(Lang.TURTLE).toGraph();
    }

    private static Node uri(String local) { return NodeFactory.createURI("http://example/"+local); }
    private static Node intLit(int i) { return NodeFactory.createLiteral(Integer.toString(i), XSDDatatype.XSDinteger); }

    private static List<Binding> exec(Graph graph, String queryString) {
        Query query = QueryFactory.create(PREFIX+queryString);
        List<Binding> results = new ArrayList<>();
        try ( QueryExec qExec = QueryExec.graph(graph).query(query).build() ) {
            RowSet rs = qExec.select();
            rs.forEachRemaining(results::add);
        }
        return results;
    }

    private static List<Binding> exec(String queryString) {
        return exec(data(), queryString);
    }

    @Test public void exact_sequence_pattern() {
        List<Binding> r = exec("SELECT ?s { ?s :p (1 2 3) }");
        assertEquals(1, r.size());
        assertEquals(uri("x"), r.get(0).get("s"));
    }

    @Test public void sequence_pattern_with_variables() {
        List<Binding> r = exec("SELECT ?a ?b { :x :p (1 ?a ?b) }");
        assertEquals(1, r.size());
        assertEquals(intLit(2), r.get(0).get("a"));
        assertEquals(intLit(3), r.get(0).get("b"));
    }

    @Test public void sequence_pattern_length_must_match() {
        assertEquals(0, exec("SELECT ?a { :x :p (1 ?a) }").size());
        assertEquals(0, exec("SELECT ?a { :x :p (?a ?a ?a) }").size());
    }

    @Test public void repeated_variable_in_pattern() {
        Graph g = RDFParser.fromString(PREFIX+":s :p (5 5) . :t :p (5 6) .").lang(Lang.TURTLE).toGraph();
        List<Binding> r = exec(g, "SELECT ?s { ?s :p (?v ?v) }");
        assertEquals(1, r.size());
        assertEquals(uri("s"), r.get(0).get("s"));
    }

    @Test public void nested_sequence_pattern() {
        List<Binding> r = exec("SELECT ?s ?c { ?s :p (:a (?b ?c)) }");
        assertEquals(1, r.size());
        assertEquals(uri("y"), r.get(0).get("s"));
        assertEquals(uri("c"), r.get(0).get("c"));
    }

    @Test public void empty_sequence_pattern() {
        List<Binding> r = exec("SELECT ?s { ?s :p () }");
        assertEquals(1, r.size());
        assertEquals(uri("z"), r.get(0).get("s"));
    }

    @Test public void position_constant() {
        List<Binding> r = exec("SELECT ?e { :x :p ((?e))[2] }");
        assertEquals(1, r.size());
        assertEquals(intLit(2), r.get(0).get("e"));
        assertEquals(0, exec("SELECT ?e { :x :p ((?e))[4] }").size());
    }

    @Test public void position_variable() {
        List<Binding> r = exec("SELECT ?i ?e { :x :p ((?e))[?i] } ORDER BY ?i");
        assertEquals(3, r.size());
        for ( int i = 0 ; i < 3 ; i++ ) {
            assertEquals(intLit(i+1), r.get(i).get("i"));
            assertEquals(intLit(i+1), r.get(i).get("e"));
        }
    }

    @Test public void position_any() {
        List<Binding> r = exec("SELECT ?s ?e { ?s :p ((?e))[*] } ORDER BY ?s ?e");
        // :x has 3 elements, :y has 2, :z none, :w is not a sequence.
        assertEquals(5, r.size());
        assertEquals(uri("x"), r.get(0).get("s"));
        assertEquals(uri("y"), r.get(3).get("s"));
    }

    @Test public void position_constant_element_pattern() {
        // The element at position 1 must be :a.
        assertEquals(1, exec("SELECT ?s { ?s :p ((:a))[1] }").size());
        assertEquals(0, exec("SELECT ?s { ?s :p ((:a))[2] }").size());
    }

    @Test public void nested_position_patterns() {
        // Second element of the sequence, then its first element.
        List<Binding> r = exec("SELECT ?e { :y :p ((((?e))[1]))[2] }");
        assertEquals(1, r.size());
        assertEquals(uri("b"), r.get(0).get("e"));
    }

    @Test public void position_bound_by_earlier_pattern() {
        Graph g = RDFParser.fromString(PREFIX+":s :p (10 20 30) ; :idx 2 .").lang(Lang.TURTLE).toGraph();
        List<Binding> r = exec(g, "SELECT ?e { :s :idx ?i . :s :p ((?e))[?i] }");
        assertEquals(1, r.size());
        assertEquals(intLit(20), r.get(0).get("e"));
    }

    @Test public void functions_seqlen_isseq_index() {
        List<Binding> r = exec("SELECT ?s ?n ?second ?isseq { ?s :p ?seq . FILTER(isSEQ(?seq)) BIND(SEQLEN(?seq) AS ?n) BIND(?seq[2] AS ?second) BIND(isSEQ(?seq) AS ?isseq) } ORDER BY ?n");
        assertEquals(3, r.size());
        // :z
        assertEquals(intLit(0), r.get(0).get("n"));
        assertFalse(r.get(0).contains("second"));   // out of range: unbound
        // :y
        assertEquals(intLit(2), r.get(1).get("n"));
        assertEquals(NodeFactory.createSeqNode(uri("b"), uri("c")), r.get(1).get("second"));
        // :x
        assertEquals(intLit(3), r.get(2).get("n"));
        assertEquals(intLit(2), r.get(2).get("second"));
        assertEquals(NodeFactory.createLiteralByValue(true), r.get(2).get("isseq"));
    }

    @Test public void isseq_false_for_other_terms() {
        List<Binding> r = exec("SELECT ?s { ?s :p ?o FILTER(!isSEQ(?o)) }");
        assertEquals(1, r.size());
        assertEquals(uri("w"), r.get(0).get("s"));
    }

    @Test public void chained_index_in_expression() {
        List<Binding> r = exec("SELECT ?e { :y :p ?seq BIND(?seq[2][1] AS ?e) }");
        assertEquals(1, r.size());
        assertEquals(uri("b"), r.get(0).get("e"));
    }

    @Test public void seq_constructor() {
        List<Binding> r = exec("SELECT ?seq { BIND(SEQ(1, :a, SEQ()) AS ?seq) }");
        assertEquals(1, r.size());
        assertEquals(NodeFactory.createSeqNode(intLit(1), uri("a"), NodeFactory.createSeqNode()), r.get(0).get("seq"));
    }

    @Test public void sequences_in_order_by_and_equality() {
        List<Binding> r = exec("SELECT ?s { ?s :p ?seq FILTER(isSEQ(?seq)) } ORDER BY ?seq");
        assertEquals(3, r.size());
        assertEquals(uri("z"), r.get(0).get("s"));    // () sorts first
        List<Binding> r2 = exec("SELECT ?s { ?s :p ?seq FILTER(?seq = (SEQ(1, 2, 3))) }");
        assertEquals(1, r2.size());
        assertEquals(uri("x"), r2.get(0).get("s"));
    }

    @Test public void construct_sequence_from_pattern() {
        Query query = QueryFactory.create(PREFIX+"CONSTRUCT { ?s :r (?a ?b) } WHERE { ?s :p (?a ?b ?c) }");
        Model m = ModelFactory.createModelForGraph(data());
        try ( QueryExecution qExec = QueryExecutionFactory.create(query, m) ) {
            Graph g = qExec.execConstruct().getGraph();
            assertEquals(1, g.size());
            Node o = g.find().next().getObject();
            assertEquals(NodeFactory.createSeqNode(intLit(1), intLit(2)), o);
        }
    }

    @Test public void update_insert_data_and_delete_data() {
        DatasetGraph dsg = DatasetGraphFactory.createTxnMem();
        UpdateAction.parseExecute(PREFIX+"INSERT DATA { :n :p (4 5) . :n :q () }", dsg);
        assertEquals(2, dsg.getDefaultGraph().size());
        assertEquals(1, exec(dsg.getDefaultGraph(), "SELECT ?e { :n :p ((?e))[2] }").size());
        UpdateAction.parseExecute(PREFIX+"DELETE DATA { :n :p (4 5) }", dsg);
        assertEquals(1, dsg.getDefaultGraph().size());
    }

    @Test public void dataset_quad_matching() {
        DatasetGraph dsg = DatasetGraphFactory.createTxnMem();
        RDFParser.fromString(PREFIX+":g { :x :p (1 2 3) } :h { :y :p (7 8) }").lang(Lang.TRIG).parse(dsg);
        Query query = QueryFactory.create(PREFIX+"SELECT ?g ?e { GRAPH ?g { ?s :p ((?e))[2] } } ORDER BY ?g");
        try ( QueryExec qExec = QueryExec.dataset(dsg).query(query).build() ) {
            List<Binding> r = new ArrayList<>();
            qExec.select().forEachRemaining(r::add);
            assertEquals(2, r.size());
            assertEquals(uri("g"), r.get(0).get("g"));
            assertEquals(intLit(2), r.get(0).get("e"));
            assertEquals(uri("h"), r.get(1).get("g"));
            assertEquals(intLit(8), r.get(1).get("e"));
        }
    }

    @Test public void query_serialization_round_trip() {
        Query q1 = QueryFactory.create(PREFIX+"SELECT * { ?s :p ((?e))[2] ; :q (1 ?x ()) ; :r ((?z))[*] ; :t ((?z))[?i] FILTER(?x[1] = SEQLEN(?z)) }");
        String str = q1.toString();
        assertTrue(str, str.contains("((?e))[2]"));
        Query q2 = QueryFactory.create(str);
        assertEquals(q1, q2);
    }

    @Test public void algebra_sse_round_trip() {
        Query q = QueryFactory.create(PREFIX+"SELECT * { ?s :p ((?e))[2] ; :q (1 ?x ()) ; :r ((?z))[*] FILTER(?x[1] = SEQLEN(?z) && isSEQ(SEQ(1))) }");
        Op op1 = Algebra.compile(q);
        String sse = op1.toString();
        assertTrue(sse, sse.contains("(seqpos ?e 2)"));
        assertTrue(sse, sse.contains("(seqpos ?z ANY)"));
        assertTrue(sse, sse.replaceAll("\\s+", " ").contains("(seqterm 1 ?x (seqterm))"));
        Op op2 = SSE.parseOp(sse);
        assertEquals(op1, op2);
    }

    @Test public void json_results_carry_sequences() {
        Query query = QueryFactory.create(PREFIX+"SELECT ?seq { :y :p ?seq }");
        Model m = ModelFactory.createModelForGraph(data());
        try ( QueryExecution qExec = QueryExecutionFactory.create(query, m) ) {
            ResultSet rs = qExec.execSelect();
            String json = ResultSetFormatter.asText(rs);
            assertTrue(json, json.contains("( :a ( :b :c ) )") || json.contains("( <http://example/a>"));
        }
        try ( QueryExecution qExec = QueryExecutionFactory.create(query, m) ) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            ResultSetFormatter.outputAsJSON(out, qExec.execSelect());
            String json = out.toString();
            assertTrue(json, json.contains("\"seq\""));
        }
    }
}
