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

package org.apache.jena.shex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shex.expressions.Cardinality;
import org.apache.jena.shex.expressions.Shape;
import org.apache.jena.shex.expressions.ShapeCollection;
import org.apache.jena.shex.expressions.ShapeExpr;
import org.apache.jena.shex.expressions.ShapeExprRef;
import org.apache.jena.shex.expressions.TripleConstraint;
import org.apache.jena.shex.expressions.TripleExpr;
import org.apache.jena.shex.jsonld.ShexJsonldDeserializer;
import org.apache.jena.shex.reporting.ShexValidationReport;
import org.apache.jena.shex.writer.WriterShExC;
import org.junit.Test;

/**
 * RDF sequences in ShEx: the Collection shape expression {@code ((shapeExpr))[min, max]}
 * matches a sequence term whose elements each satisfy {@code shapeExpr}.
 */
public class TestShexCollection {

    private static final String PREFIXES =
            "PREFIX : <http://example/>\n" +
            "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n";

    private static final String SCHEMA = PREFIXES +
            ":T { :q xsd:integer }\n" +
            ":S { :p ((@:T))[1, 2] }\n" +
            ":S0 { :p ((xsd:integer)) }\n" +
            ":S2 { :p ((xsd:integer))[2] }\n" +
            ":S3 { :p ((xsd:integer))[1, *] }\n" +
            ":SN { :p ((((@:T))[1]))[1, *] }\n";

    private static Node ex(String local) { return NodeFactory.createURI("http://example/"+local); }

    private static Graph graph(String ttl) {
        return RDFParser.fromString(PREFIXES + ttl).lang(Lang.TURTLE).toGraph();
    }

    private static boolean conforms(ShexSchema schema, String data, String focus, String shape) {
        ShexValidator validator = ShexValidator.getNew(List.of());
        ShexValidationReport report = validator.validate(graph(data), schema, ex(shape), ex(focus));
        return report.conforms();
    }

    private static boolean conforms(String data, String focus, String shape) {
        return conforms(Shex.schemaFromString(SCHEMA), data, focus, shape);
    }

    @Test public void elements_each_satisfy_the_element_shape() {
        assertTrue(conforms(":x :p (:a :b) . :a :q 1 . :b :q 2 .", "x", "S"));
    }

    @Test public void one_element_is_enough() {
        assertTrue(conforms(":x :p (:a) . :a :q 1 .", "x", "S"));
    }

    @Test public void too_many_elements() {
        assertFalse(conforms(":x :p (:a :b :c) . :a :q 1 . :b :q 2 . :c :q 3 .", "x", "S"));
    }

    @Test public void too_few_elements() {
        assertFalse(conforms(":x :p () .", "x", "S"));
    }

    @Test public void an_element_fails_the_element_shape() {
        assertFalse(conforms(":x :p (:a :b) . :a :q 1 . :b :q 'two' .", "x", "S"));
    }

    @Test public void not_a_sequence() {
        assertFalse(conforms(":x :p :a . :a :q 1 .", "x", "S"));
        assertFalse(conforms(":x :p 1 .", "x", "S0"));
    }

    @Test public void any_length_including_empty() {
        assertTrue(conforms(":x :p () .", "x", "S0"));
        assertTrue(conforms(":x :p (1 2 3) .", "x", "S0"));
        assertFalse(conforms(":x :p (1 'a') .", "x", "S0"));
    }

    @Test public void exact_length() {
        assertTrue(conforms(":x :p (1 2) .", "x", "S2"));
        assertFalse(conforms(":x :p (1) .", "x", "S2"));
        assertFalse(conforms(":x :p (1 2 3) .", "x", "S2"));
    }

    @Test public void at_least_one() {
        assertFalse(conforms(":x :p () .", "x", "S3"));
        assertTrue(conforms(":x :p (1) .", "x", "S3"));
        assertTrue(conforms(":x :p (1 2 3 4) .", "x", "S3"));
    }

    @Test public void nested_collections() {
        assertTrue(conforms(":x :p ((:a) (:b)) . :a :q 1 . :b :q 2 .", "x", "SN"));
        assertFalse(conforms(":x :p ((:a :b)) . :a :q 1 . :b :q 2 .", "x", "SN"));
        assertFalse(conforms(":x :p (:a) . :a :q 1 .", "x", "SN"));
    }

    @Test public void parsed_structure() {
        ShexSchema schema = Shex.schemaFromString(SCHEMA);
        ShapeExpr se = schema.get(ex("S")).getShapeExpr();
        TripleExpr te = ((Shape)se).getTripleExpr();
        ShapeExpr valueExpr = ((TripleConstraint)te).getValueExpr();
        assertTrue(valueExpr instanceof ShapeCollection);
        ShapeCollection c = (ShapeCollection)valueExpr;
        assertEquals(1, c.getMin());
        assertEquals(2, c.getMax());
        assertEquals(ShapeExprRef.create(ex("T")), c.getShapeExpr());
        ShapeCollection c3 = (ShapeCollection)((TripleConstraint)((Shape)schema.get(ex("S3")).getShapeExpr()).getTripleExpr()).getValueExpr();
        assertEquals(1, c3.getMin());
        assertEquals(Cardinality.UNBOUNDED, c3.getMax());
        assertEquals("[1, *]", c3.boundsString());
        ShapeCollection c0 = (ShapeCollection)((TripleConstraint)((Shape)schema.get(ex("S0")).getShapeExpr()).getTripleExpr()).getValueExpr();
        assertTrue(c0.isAnyLength());
    }

    @Test public void print_and_reparse() {
        ShexSchema schema1 = Shex.schemaFromString(SCHEMA);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WriterShExC.print(out, schema1);
        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed, printed.contains("(( @:T ))[1, 2]") || printed.contains("(( @<http://example/T> ))[1, 2]"));
        assertTrue(printed, printed.contains("))[2]"));
        assertTrue(printed, printed.contains("))[1, *]"));
        ShexSchema schema2 = Shex.schemaFromString(printed);
        assertEquals(schema1.get(ex("S")).getShapeExpr(), schema2.get(ex("S")).getShapeExpr());
        assertEquals(schema1.get(ex("S3")).getShapeExpr(), schema2.get(ex("S3")).getShapeExpr());
        assertTrue(conforms(schema2, ":x :p (:a :b) . :a :q 1 . :b :q 2 .", "x", "S"));
    }

    @Test public void shexj() {
        String json = "{ \"type\": \"Schema\", \"shapes\": [\n" +
                "  { \"type\": \"ShapeDecl\", \"id\": \"http://example/T\", \"shapeExpr\": { \"type\": \"Shape\", \"expression\": {\n" +
                "      \"type\": \"TripleConstraint\", \"predicate\": \"http://example/q\",\n" +
                "      \"valueExpr\": { \"type\": \"NodeConstraint\", \"datatype\": \"http://www.w3.org/2001/XMLSchema#integer\" } } } },\n" +
                "  { \"type\": \"ShapeDecl\", \"id\": \"http://example/S\", \"shapeExpr\": { \"type\": \"Shape\", \"expression\": {\n" +
                "      \"type\": \"TripleConstraint\", \"predicate\": \"http://example/p\",\n" +
                "      \"valueExpr\": { \"type\": \"Collection\", \"min\": 1, \"max\": 2,\n" +
                "                     \"shapeExpr\": \"http://example/T\" } } } }\n" +
                "] }";
        ShexSchema schema = new ShexJsonldDeserializer().deserializeSchema(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        ShapeExpr valueExpr = ((TripleConstraint)((Shape)schema.get(ex("S")).getShapeExpr()).getTripleExpr()).getValueExpr();
        assertTrue(valueExpr instanceof ShapeCollection);
        assertTrue(conforms(schema, ":x :p (:a :b) . :a :q 1 . :b :q 2 .", "x", "S"));
        assertFalse(conforms(schema, ":x :p (:a :b :c) . :a :q 1 . :b :q 2 . :c :q 3 .", "x", "S"));
    }

    @Test public void shexr() {
        String ttl = "PREFIX sx: <http://www.w3.org/ns/shex#>\n" +
                "PREFIX : <http://example/>\n" +
                "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n" +
                "[] a sx:Schema ; sx:shapes ( :S :T ) .\n" +
                ":T a sx:ShapeDecl ; sx:shapeExpr [ a sx:Shape ; sx:expression [ a sx:TripleConstraint ; sx:predicate :q ;\n" +
                "     sx:valueExpr [ a sx:NodeConstraint ; sx:datatype xsd:integer ] ] ] .\n" +
                ":S a sx:ShapeDecl ; sx:shapeExpr [ a sx:Shape ; sx:expression [ a sx:TripleConstraint ; sx:predicate :p ;\n" +
                "     sx:valueExpr [ a sx:Collection ; sx:shapeExpr :T ; sx:min 1 ; sx:max 2 ] ] ] .\n";
        ShexSchema schema = Shex.schemaFromStringShExR(ttl, "http://example/");
        ShapeExpr valueExpr = ((TripleConstraint)((Shape)schema.get(ex("S")).getShapeExpr()).getTripleExpr()).getValueExpr();
        assertTrue(valueExpr instanceof ShapeCollection);
        assertEquals(2, ((ShapeCollection)valueExpr).getMax());
        assertTrue(conforms(schema, ":x :p (:a :b) . :a :q 1 . :b :q 2 .", "x", "S"));
        assertFalse(conforms(schema, ":x :p (:a :b :c) . :a :q 1 . :b :q 2 . :c :q 3 .", "x", "S"));
    }
}
