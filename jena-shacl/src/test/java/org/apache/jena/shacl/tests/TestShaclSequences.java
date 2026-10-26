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
package org.apache.jena.shacl.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.compact.ShaclcParser;
import org.apache.jena.shacl.parser.ShaclParseException;
import org.apache.jena.shacl.vocabulary.SHACL;
import org.apache.jena.system.G;
import org.apache.jena.vocabulary.RDF;
import org.junit.Test;

/**
 * rdf-array: a SHACL list is a sequence term. An rdf:first/rdf:rest ladder where a list is
 * expected is triples, not a list. The validation cases are manifest tests in
 * src/test/files/local/rdf-array/.
 */
public class TestShaclSequences {

    private static final String PREFIXES = """
        PREFIX ex:  <http://example/>
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX sh:  <http://www.w3.org/ns/shacl#>
        """;

    private static Graph parse(String ttl) {
        return RDFParser.fromString(PREFIXES+ttl).lang(Lang.TURTLE).toGraph();
    }

    @Test public void ladder_in_sh_in_is_not_a_list() {
        Graph shapes = parse("ex:S a sh:NodeShape ; sh:targetNode 1 ; sh:in _:l . _:l rdf:first 1 ; rdf:rest rdf:nil .");
        assertThrows(ShaclParseException.class, () -> Shapes.parse(shapes));
    }

    @Test public void ladder_path_is_not_a_sequence_path() {
        Graph shapes = parse("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path _:l ; sh:minCount 1 ] ."
                             + " _:l rdf:first ex:p ; rdf:rest ( ex:q ) .");
        assertThrows(ShaclParseException.class, () -> Shapes.parse(shapes));
    }

    @Test public void empty_sequence_path_is_an_error() {
        Graph shapes = parse("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path () ; sh:minCount 1 ] .");
        assertThrows(ShaclParseException.class, () -> Shapes.parse(shapes));
    }

    @Test public void rdf_nil_is_an_iri_path() {
        Graph graph = parse("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path rdf:nil ; sh:minCount 1 ] ."
                            + " ex:a rdf:nil 1 .");
        assertTrue(ShaclValidator.get().validate(graph, graph).conforms());
    }

    @Test public void sequence_in_conforms() {
        Graph graph = parse("ex:S a sh:NodeShape ; sh:targetNode 1, 2 ; sh:in ( 1 2 ) .");
        assertTrue(ShaclValidator.get().validate(graph, graph).conforms());
    }

    @Test public void compact_syntax_lists_are_sequences() {
        String shaclc = "PREFIX ex: <http://example/>\nshape ex:S { ex:p in=[ 1 2 ] . }\n";
        Shapes shapes = ShaclcParser.parse(new ByteArrayInputStream(shaclc.getBytes(StandardCharsets.UTF_8)), "http://example/");
        Graph graph = shapes.getGraph();
        Node in = G.find(graph, null, SHACL.in, null).toList().get(0).getObject();
        assertTrue(in.isNodeSeq());
        assertEquals(2, in.getSeq().size());
        assertFalse(G.find(graph, null, RDF.Nodes.first, null).hasNext());
    }
}
