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
import static org.junit.Assert.assertThrows;
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
import org.apache.jena.shex.expressions.ListEachOf;
import org.apache.jena.shex.expressions.ListExpr;
import org.apache.jena.shex.expressions.ListMember;
import org.apache.jena.shex.expressions.ListOneOf;
import org.apache.jena.shex.expressions.ListShape;
import org.apache.jena.shex.expressions.NodeConstraint;
import org.apache.jena.shex.expressions.Shape;
import org.apache.jena.shex.expressions.ShapeAnd;
import org.apache.jena.shex.expressions.ShapeExpr;
import org.apache.jena.shex.expressions.ShapeExprRef;
import org.apache.jena.shex.expressions.ShapeOr;
import org.apache.jena.shex.expressions.TripleConstraint;
import org.apache.jena.shex.expressions.TripleExpr;
import org.apache.jena.shex.expressions.TripleExprCardinality;
import org.apache.jena.shex.jsonld.ShexJsonldDeserializer;
import org.apache.jena.shex.parser.ShexParseException;
import org.apache.jena.shex.reporting.ShexValidationReport;
import org.apache.jena.shex.writer.WriterShExC;
import org.junit.Test;

/**
 * RDF sequences in ShEx: the LIST shape expression, {@code LIST ( regex )} and
 * {@code LIST card x}, over the data and shapes of the array-syntax design note
 * (rdf-array/docs/array-syntax.md, sections 4.2 and 5).
 */
public class TestShexList {

    private static final String PREFIXES =
            "PREFIX : <http://ex/>\n" +
            "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n";

    /** The data of section 4. */
    private static final String DATA = PREFIXES +
            ":obs1 :component ( ( \"8480-6\" \"systolic\"  ( 120 \"mm[Hg]\" ) )\n" +
            "                   ( \"8462-4\" \"diastolic\" (  80 \"mm[Hg]\" ) ) ) .\n" +
            ":obs2 :component ( ( \"8480-6\" \"systolic\"  ( 150 \"mm[Hg]\" ) )\n" +
            "                   ( \"8462-4\" \"diastolic\" (  95 \"mm[Hg]\" ) ) ) .\n" +
            ":obs3 :component ( ( \"8480-6\" \"systolic\"  ( \"high\" \"mm[Hg]\" ) ) ) .\n" +
            ":m1   :matrix ( ( 1 2 3 ) ( 4 5 6 ) ) .\n" +
            ":m2   :matrix ( ( 1 2 3 ) ( 4 5 ) ) .\n" +
            ":poly :points ( ( 0 0 ) ( 3 0 ) ( 3 4 ) ) .\n" +
            ":kv   :pairs  ( \"a\" 1 \"b\" 2 \"c\" 3 ) .\n" +
            ":tree :node   ( \"root\" ( ( \"left\" ( ) ) ( \"right\" ( ( \"leaf\" ( ) ) ) ) ) ) .\n";

    /** The shapes of section 4.2. */
    private static final String SCHEMA = PREFIXES +
            ":BP          LIST + @:Component\n" +
            ":Component   LIST ( xsd:string ; xsd:string ; LIST ( xsd:integer ; xsd:string ) )\n" +
            ":Matrix2x3   LIST ( LIST ( xsd:integer {3} ) {2} )\n" +
            ":Matrix2x3S  LIST {2} LIST {3} xsd:integer\n" +
            ":Ragged      LIST ( LIST ( xsd:integer * ) * )\n" +
            ":RaggedS     LIST * LIST * xsd:integer\n" +
            ":Polyline    LIST ( LIST ( xsd:integer ; xsd:integer ) {2,} )\n" +
            ":Pairs       LIST ( ( xsd:string ; xsd:integer ) * )\n" +
            ":IntsOrPairs LIST ( ( xsd:integer | LIST ( xsd:integer ; xsd:integer ) ) + )\n" +
            ":Tree        LIST ( xsd:string ; LIST ( @:Tree * ) )\n";

    private static final Graph DATA_GRAPH = graph(DATA);
    private static final ShexSchema SCHEMA_PARSED = Shex.schemaFromString(SCHEMA);

    private static Node ex(String local) { return NodeFactory.createURI("http://ex/"+local); }

    private static Graph graph(String ttl) {
        return RDFParser.fromString(ttl.startsWith("PREFIX") ? ttl : PREFIXES + ttl).lang(Lang.TURTLE).toGraph();
    }

    /** The object of the one triple (subject, predicate, ?o): the sequence to validate. */
    private static Node value(Graph g, String subject, String predicate) {
        return g.find(ex(subject), ex(predicate), Node.ANY).next().getObject();
    }

    private static boolean conforms(ShexSchema schema, Graph data, Node focus, String shape) {
        ShexValidator validator = ShexValidator.getNew(List.of());
        ShexValidationReport report = validator.validate(data, schema, ex(shape), focus);
        return report.conforms();
    }

    /** Validate the value of (subject, predicate) in the section 4 data against a shape of the section 4.2 schema. */
    private static boolean conforms(String subject, String predicate, String shape) {
        return conforms(SCHEMA_PARSED, DATA_GRAPH, value(DATA_GRAPH, subject, predicate), shape);
    }

    private static ShapeExpr valueExprOf(ShexSchema schema, String shape) {
        TripleExpr te = ((Shape)schema.get(ex(shape)).getShapeExpr()).getTripleExpr();
        if ( te instanceof TripleExprCardinality c )
            te = c.getSubExpr();
        return ((TripleConstraint)te).getValueExpr();
    }

    // ---- Section 4.2

    @Test public void blood_pressure_components() {
        assertTrue(conforms("obs1", "component", "BP"));
        assertTrue(conforms("obs2", "component", "BP"));
        assertFalse(conforms("obs3", "component", "BP"));   // "high" is not an integer
    }

    @Test public void matrix_2x3() {
        assertTrue(conforms("m1", "matrix", "Matrix2x3"));
        assertFalse(conforms("m2", "matrix", "Matrix2x3"));
    }

    @Test public void matrix_2x3_shorthand_is_the_same_expression() {
        assertTrue(conforms("m1", "matrix", "Matrix2x3S"));
        assertFalse(conforms("m2", "matrix", "Matrix2x3S"));
        assertEquals(SCHEMA_PARSED.get(ex("Matrix2x3")).getShapeExpr(), SCHEMA_PARSED.get(ex("Matrix2x3S")).getShapeExpr());
        assertEquals(SCHEMA_PARSED.get(ex("Ragged")).getShapeExpr(), SCHEMA_PARSED.get(ex("RaggedS")).getShapeExpr());
    }

    @Test public void ragged() {
        assertTrue(conforms("m1", "matrix", "Ragged"));
        assertTrue(conforms("m2", "matrix", "Ragged"));
        assertTrue(conforms("m2", "matrix", "RaggedS"));
    }

    @Test public void polyline() {
        assertTrue(conforms("poly", "points", "Polyline"));
        assertFalse(conforms("m1", "matrix", "Polyline"));
    }

    @Test public void pairs_a_repeated_group() {
        assertTrue(conforms("kv", "pairs", "Pairs"));
        assertFalse(conforms("poly", "points", "Pairs"));
    }

    @Test public void ints_or_pairs_a_choice() {
        assertTrue(conforms("poly", "points", "IntsOrPairs"));
        assertFalse(conforms("m1", "matrix", "IntsOrPairs"));
    }

    @Test public void tree_recursion() {
        assertTrue(conforms("tree", "node", "Tree"));
        assertFalse(conforms("m1", "matrix", "Tree"));
    }

    // ---- Section 5: edge cases

    @Test public void bare_LIST_x_is_a_syntax_error() {
        assertThrows(ShexParseException.class, () -> Shex.schemaFromString(PREFIXES + ":S LIST xsd:integer\n"));
    }

    @Test public void LIST_of_one_is_exactly_one() {
        ShexSchema schema = Shex.schemaFromString(PREFIXES + ":S LIST ( xsd:integer )\n");
        ListShape ls = (ListShape)schema.get(ex("S")).getShapeExpr();
        assertTrue(ls.getExpression() instanceof ListMember);
        assertFalse(ls.getExpression().hasCardinality());
        Graph g = graph(":x :p (1) . :y :p (1 2) . :z :p () .");
        assertTrue(conforms(schema, g, value(g, "x", "p"), "S"));
        assertFalse(conforms(schema, g, value(g, "y", "p"), "S"));
        assertFalse(conforms(schema, g, value(g, "z", "p"), "S"));
    }

    @Test public void cardinality_first_with_a_parenthesised_member() {
        ShexSchema schema = Shex.schemaFromString(PREFIXES + ":S LIST {2} ( @:A OR @:B )\n");
        ListShape ls = (ListShape)schema.get(ex("S")).getShapeExpr();
        ListMember m = (ListMember)ls.getExpression();
        assertTrue(m.getShapeExpr() instanceof ShapeOr);
        assertEquals(new Cardinality(2, 2), m.getCardinality());
    }

    @Test public void a_parenthesised_first_member_is_a_syntax_error() {
        // Inside LIST ( ), the second ( opens a group, and a group cannot be followed by AND.
        assertThrows(ShexParseException.class, () -> Shex.schemaFromString(PREFIXES + ":S LIST ( ( @:A ) AND @:B )\n"));
    }

    @Test public void a_later_parenthesis_is_an_ordinary_shape_expression() {
        ShexSchema schema = Shex.schemaFromString(PREFIXES + ":S LIST ( @:B AND ( @:A OR @:C ) )\n");
        ListShape ls = (ListShape)schema.get(ex("S")).getShapeExpr();
        ListMember m = (ListMember)ls.getExpression();
        assertTrue(m.getShapeExpr() instanceof ShapeAnd);
        assertEquals(2, ((ShapeAnd)m.getShapeExpr()).getShapeExprs().size());
    }

    @Test public void a_group_of_one_member_is_that_member() {
        ShexSchema schema = Shex.schemaFromString(PREFIXES + ":S LIST ( ( @:A OR @:B ) * )\n");
        ListShape ls = (ListShape)schema.get(ex("S")).getShapeExpr();
        ListMember m = (ListMember)ls.getExpression();
        assertTrue(m.getShapeExpr() instanceof ShapeOr);
        assertEquals(Cardinality.STAR, m.getCardinality());
    }

    @Test public void a_group_with_a_cardinality_of_its_own_is_wrapped() {
        // ( x {2} ) {3} : the member keeps {2}; the group is a ListEachOf of one with {3}.
        ShexSchema schema = Shex.schemaFromString(PREFIXES + ":S LIST ( ( xsd:integer {2} ) {3} )\n");
        ListShape ls = (ListShape)schema.get(ex("S")).getShapeExpr();
        ListEachOf group = (ListEachOf)ls.getExpression();
        assertEquals(new Cardinality(3, 3), group.getCardinality());
        assertEquals(1, group.getExpressions().size());
        assertEquals(new Cardinality(2, 2), group.getExpressions().get(0).getCardinality());
        Graph g = graph(":x :p (1 2 3 4 5 6) . :y :p (1 2 3 4) .");
        assertTrue(conforms(schema, g, value(g, "x", "p"), "S"));
        assertFalse(conforms(schema, g, value(g, "y", "p"), "S"));
    }

    @Test public void cardinality_inside_counts_members_and_after_counts_triples() {
        ShexSchema schema = Shex.schemaFromString(PREFIXES +
                ":S { :component LIST ( @:Component + ) {1,2} }\n" +
                ":T { :p LIST * xsd:integer {1,2} }\n" +
                ":Component LIST ( xsd:string ; xsd:string ; LIST ( xsd:integer ; xsd:string ) )\n");
        for ( String shape : List.of("S", "T") ) {
            TripleExpr te = ((Shape)schema.get(ex(shape)).getShapeExpr()).getTripleExpr();
            assertTrue(te instanceof TripleExprCardinality);
            assertEquals(new Cardinality(1, 2), ((TripleExprCardinality)te).getCardinality());
            ListShape ls = (ListShape)valueExprOf(schema, shape);
            ListMember m = (ListMember)ls.getExpression();
            assertEquals(shape.equals("S") ? Cardinality.PLUS : Cardinality.STAR, m.getCardinality());
        }
        Graph g = graph(":x :p (1 2) , (3) . :y :p (1) , (2) , (3) . :z :p () .");
        assertTrue(conforms(schema, g, ex("x"), "T"));
        assertFalse(conforms(schema, g, ex("y"), "T"));   // three triples
        assertTrue(conforms(schema, g, ex("z"), "T"));    // one triple, an empty list
    }

    @Test public void empty_sequences() {
        ShexSchema schema = Shex.schemaFromString(PREFIXES + ":Star LIST ( xsd:integer * )\n:Plus LIST ( xsd:integer + )\n");
        Graph g = graph(":z :p () .");
        assertTrue(conforms(schema, g, value(g, "z", "p"), "Star"));
        assertFalse(conforms(schema, g, value(g, "z", "p"), "Plus"));
    }

    @Test public void not_a_sequence() {
        ShexSchema schema = Shex.schemaFromString(PREFIXES + ":S LIST ( xsd:integer * )\n");
        Graph g = graph(":x :p 1 . :y :p :a .");
        assertFalse(conforms(schema, g, value(g, "x", "p"), "S"));
        assertFalse(conforms(schema, g, value(g, "y", "p"), "S"));
    }

    @Test public void nullable_repeated_group_with_a_minimum() {
        // ( x ? ) {3} matches one x: two of the three repetitions are empty.
        ShexSchema schema = Shex.schemaFromString(PREFIXES + ":S LIST ( ( xsd:integer ? ) {3} )\n");
        Graph g = graph(":x :p (1) . :y :p (1 2 3 4) .");
        assertTrue(conforms(schema, g, value(g, "x", "p"), "S"));
        assertFalse(conforms(schema, g, value(g, "y", "p"), "S"));
    }

    @Test public void members_validated_as_focus_nodes() {
        ShexSchema schema = Shex.schemaFromString(PREFIXES + ":T { :q xsd:integer }\n:S LIST ( @:T ; xsd:integer )\n");
        Graph g = graph(":x :p (:a 1) . :a :q 1 . :y :p (:b 1) . :b :q 'one' .");
        assertTrue(conforms(schema, g, value(g, "x", "p"), "S"));
        assertFalse(conforms(schema, g, value(g, "y", "p"), "S"));
    }

    @Test public void LIST_in_every_shape_atom_position() {
        // As a value expression, as a top level shape, under NOT, in an AND, nested, and as a member.
        ShexSchema schema = Shex.schemaFromString(PREFIXES +
                ":A { :p LIST ( xsd:integer ; LIST * xsd:string ) }\n" +
                ":B NOT LIST + xsd:integer\n" +
                ":C LIST * xsd:integer AND LIST {2,} .\n");
        Graph g = graph(":x :p (1 (\"a\" \"b\")) . :y :p (1 (\"a\" 2)) .");
        assertTrue(conforms(schema, g, ex("x"), "A"));
        assertFalse(conforms(schema, g, ex("y"), "A"));
        assertTrue(conforms(schema, g, value(g, "x", "p"), "B"));   // not a list of integers only
        assertFalse(conforms(schema, g, value(g, "x", "p"), "C"));
        Graph g2 = graph(":z :p (1 2) . :w :p (1) .");
        assertTrue(conforms(schema, g2, value(g2, "z", "p"), "C"));
        assertFalse(conforms(schema, g2, value(g2, "w", "p"), "C"));
    }

    // ---- Writing and the other syntaxes

    @Test public void print_and_reparse() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WriterShExC.print(out, SCHEMA_PARSED);
        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed, printed.contains("LIST ("));
        assertFalse(printed, printed.contains("(("));
        ShexSchema schema2 = Shex.schemaFromString(printed);
        for ( String shape : List.of("BP", "Component", "Matrix2x3", "Ragged", "Polyline", "Pairs", "IntsOrPairs", "Tree") )
            assertEquals(shape, SCHEMA_PARSED.get(ex(shape)).getShapeExpr(), schema2.get(ex(shape)).getShapeExpr());
        assertTrue(conforms(schema2, DATA_GRAPH, value(DATA_GRAPH, "kv", "pairs"), "Pairs"));
    }

    @Test public void print_a_member_whose_first_operand_would_be_parenthesised() {
        // ShExC cannot write these members directly (inside LIST ( ) a leading "(" opens a group), so they come from ShExJ:
        // ( A OR B ) AND C, and ( A OR B ) AND ( C OR D ).
        String or = "{ \"type\": \"ShapeOr\", \"shapeExprs\": [ \"http://ex/A\", \"http://ex/B\" ] }";
        String or2 = "{ \"type\": \"ShapeOr\", \"shapeExprs\": [ \"http://ex/C\", \"http://ex/D\" ] }";
        String json = "{ \"type\": \"Schema\", \"shapes\": [\n" +
                "  { \"type\": \"ShapeDecl\", \"id\": \"http://ex/S\", \"shapeExpr\": { \"type\": \"ListShape\", \"expression\": {\n" +
                "      \"type\": \"ListEachOf\", \"expressions\": [\n" +
                "        { \"type\": \"Member\", \"shapeExpr\": { \"type\": \"ShapeAnd\", \"shapeExprs\": [ " + or + ", \"http://ex/C\" ] } },\n" +
                "        { \"type\": \"Member\", \"shapeExpr\": { \"type\": \"ShapeAnd\", \"shapeExprs\": [ " + or + ", " + or2 + " ] } } ] } } }\n" +
                "] }";
        ShexSchema s1 = new ShexJsonldDeserializer().deserializeSchema(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WriterShExC.print(out, s1);
        String printed = out.toString(StandardCharsets.UTF_8);
        ShexSchema s2 = Shex.schemaFromString(printed);
        ListEachOf e2 = (ListEachOf)((ListShape)s2.get(ex("S")).getShapeExpr()).getExpression();
        assertEquals(2, e2.getExpressions().size());
        // The first member is rotated: @<C> AND ( @<A> OR @<B> ).
        ShapeAnd m1 = (ShapeAnd)((ListMember)e2.getExpressions().get(0)).getShapeExpr();
        assertEquals(2, m1.getShapeExprs().size());
        assertTrue(m1.getShapeExprs().get(0) instanceof ShapeExprRef);
        assertTrue(m1.getShapeExprs().get(1) instanceof ShapeOr);
        // The second has no plain operand: . AND ( @<A> OR @<B> ) AND ( @<C> OR @<D> ).
        ShapeAnd m2 = (ShapeAnd)((ListMember)e2.getExpressions().get(1)).getShapeExpr();
        assertEquals(3, m2.getShapeExprs().size());
        assertTrue(m2.getShapeExprs().get(0) instanceof NodeConstraint);
        assertTrue(m2.getShapeExprs().get(1) instanceof ShapeOr);
        assertTrue(m2.getShapeExprs().get(2) instanceof ShapeOr);
    }

    @Test public void shexj() {
        String json = "{ \"type\": \"Schema\", \"shapes\": [\n" +
                "  { \"type\": \"ShapeDecl\", \"id\": \"http://ex/Point\", \"shapeExpr\": { \"type\": \"ListShape\", \"expression\": {\n" +
                "      \"type\": \"Member\", \"min\": 2, \"max\": 2,\n" +
                "      \"shapeExpr\": { \"type\": \"NodeConstraint\", \"datatype\": \"http://www.w3.org/2001/XMLSchema#integer\" } } } },\n" +
                "  { \"type\": \"ShapeDecl\", \"id\": \"http://ex/Pairs\", \"shapeExpr\": { \"type\": \"ListShape\", \"expression\": {\n" +
                "      \"type\": \"ListEachOf\", \"min\": 0, \"max\": -1, \"expressions\": [\n" +
                "        { \"type\": \"Member\", \"shapeExpr\": { \"type\": \"NodeConstraint\", \"datatype\": \"http://www.w3.org/2001/XMLSchema#string\" } },\n" +
                "        { \"type\": \"Member\", \"shapeExpr\": { \"type\": \"NodeConstraint\", \"datatype\": \"http://www.w3.org/2001/XMLSchema#integer\" } } ] } } },\n" +
                "  { \"type\": \"ShapeDecl\", \"id\": \"http://ex/Polyline\", \"shapeExpr\": { \"type\": \"ListShape\", \"expression\": {\n" +
                "      \"type\": \"ListOneOf\", \"min\": 1, \"max\": -1, \"expressions\": [\n" +
                "        { \"type\": \"Member\", \"shapeExpr\": \"http://ex/Point\" },\n" +
                "        { \"type\": \"Member\", \"shapeExpr\": { \"type\": \"NodeConstraint\", \"datatype\": \"http://www.w3.org/2001/XMLSchema#integer\" } } ] } } }\n" +
                "] }";
        ShexSchema schema = new ShexJsonldDeserializer().deserializeSchema(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        ListShape pairs = (ListShape)schema.get(ex("Pairs")).getShapeExpr();
        assertTrue(pairs.getExpression() instanceof ListEachOf);
        assertEquals(Cardinality.STAR, pairs.getExpression().getCardinality());
        ListExpr polyline = ((ListShape)schema.get(ex("Polyline")).getShapeExpr()).getExpression();
        assertTrue(polyline instanceof ListOneOf);
        assertEquals(Cardinality.PLUS, polyline.getCardinality());
        assertTrue(conforms(schema, DATA_GRAPH, value(DATA_GRAPH, "kv", "pairs"), "Pairs"));
        assertFalse(conforms(schema, DATA_GRAPH, value(DATA_GRAPH, "poly", "points"), "Pairs"));
        assertTrue(conforms(schema, DATA_GRAPH, value(DATA_GRAPH, "poly", "points"), "Polyline"));
        assertFalse(conforms(schema, DATA_GRAPH, value(DATA_GRAPH, "m1", "matrix"), "Polyline"));
        // The same as ShExC.
        ShexSchema shexc = Shex.schemaFromString(PREFIXES + ":Pairs LIST ( ( xsd:string ; xsd:integer ) * )\n");
        assertEquals(shexc.get(ex("Pairs")).getShapeExpr(), pairs);
    }

    @Test public void shexr() {
        String ttl = "PREFIX sx: <http://www.w3.org/ns/shex#>\n" +
                "PREFIX : <http://ex/>\n" +
                "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n" +
                "[] a sx:Schema ; sx:shapes ( :Point :Polyline ) .\n" +
                ":Point a sx:ShapeDecl ; sx:shapeExpr [ a sx:ListShape ; sx:expression [ a sx:Member ; sx:min 2 ; sx:max 2 ;\n" +
                "     sx:shapeExpr [ a sx:NodeConstraint ; sx:datatype xsd:integer ] ] ] .\n" +
                ":Polyline a sx:ShapeDecl ; sx:shapeExpr [ a sx:ListShape ; sx:expression [ a sx:Member ; sx:min 2 ; sx:max -1 ;\n" +
                "     sx:shapeExpr [ a sx:ListShape ; sx:expression [ a sx:ListEachOf ;\n" +
                "     sx:expressions ( [ a sx:Member ; sx:shapeExpr [ a sx:NodeConstraint ; sx:datatype xsd:integer ] ]\n" +
                "                      [ a sx:Member ; sx:shapeExpr [ a sx:NodeConstraint ; sx:datatype xsd:integer ] ] ) ] ] ] ] .\n";
        ShexSchema schema = Shex.schemaFromStringShExR(ttl, "http://ex/");
        ListShape point = (ListShape)schema.get(ex("Point")).getShapeExpr();
        assertEquals(new Cardinality(2, 2), point.getExpression().getCardinality());
        ListShape polyline = (ListShape)schema.get(ex("Polyline")).getShapeExpr();
        assertTrue(polyline.getExpression() instanceof ListMember);
        assertEquals(new Cardinality(2, Cardinality.UNBOUNDED), polyline.getExpression().getCardinality());
        assertTrue(((ListMember)polyline.getExpression()).getShapeExpr() instanceof ListShape);
        // The same as the ShExC of section 4.2.
        assertEquals(SCHEMA_PARSED.get(ex("Polyline")).getShapeExpr(), polyline);
        assertTrue(conforms(schema, DATA_GRAPH, value(DATA_GRAPH, "poly", "points"), "Polyline"));
        assertFalse(conforms(schema, DATA_GRAPH, value(DATA_GRAPH, "m1", "matrix"), "Polyline"));
        Graph g = graph(":x :p (3 4) .");
        assertTrue(conforms(schema, g, value(g, "x", "p"), "Point"));
        assertFalse(conforms(schema, g, value(g, "x", "p"), "Polyline"));
    }
}
