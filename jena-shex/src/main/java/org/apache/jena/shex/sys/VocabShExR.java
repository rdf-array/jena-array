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

package org.apache.jena.shex.sys;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

/**
 * Vocabulary for ShExR : the RDF syntax for ShEx, {@code http://www.w3.org/ns/shex#}.
 * <p>
 * Terms follow the grammar in ShExR.shex (the shexTest test suite's own ShEx schema for
 * the ShExR RDF representation of a ShEx schema).
 */
public class VocabShExR {
    private static Node createURI(String uri) { return NodeFactory.createURI(uri); }

    public static final String NS = "http://www.w3.org/ns/shex#";
    public static String getURI() { return NS; }
    public static final Node NAMESPACE = createURI(NS);

    // -- Classes

    public static final Node Schema             = createURI(NS+"Schema");
    public static final Node ShapeDecl          = createURI(NS+"ShapeDecl");
    public static final Node ShapeOr            = createURI(NS+"ShapeOr");
    public static final Node ShapeAnd           = createURI(NS+"ShapeAnd");
    public static final Node ShapeNot           = createURI(NS+"ShapeNot");
    public static final Node NodeConstraint     = createURI(NS+"NodeConstraint");
    public static final Node Shape              = createURI(NS+"Shape");
    public static final Node ShapeExternal      = createURI(NS+"ShapeExternal");
    public static final Node SemAct             = createURI(NS+"SemAct");
    public static final Node Annotation         = createURI(NS+"Annotation");        // recognized, not built (no AST type)
    public static final Node OneOf              = createURI(NS+"OneOf");
    public static final Node EachOf             = createURI(NS+"EachOf");
    public static final Node TripleConstraint   = createURI(NS+"TripleConstraint");

    // Value-set-value shapes (collapse onto ValueSetItem/ValueSetRange - see ParserShExR)
    public static final Node IriStem            = createURI(NS+"IriStem");
    public static final Node IriStemRange       = createURI(NS+"IriStemRange");
    public static final Node LiteralStem        = createURI(NS+"LiteralStem");
    public static final Node LiteralStemRange   = createURI(NS+"LiteralStemRange");
    public static final Node Language           = createURI(NS+"Language");
    public static final Node LanguageStem       = createURI(NS+"LanguageStem");
    public static final Node LanguageStemRange  = createURI(NS+"LanguageStemRange");
    public static final Node Wildcard           = createURI(NS+"Wildcard");

    // -- Properties

    public static final Node imports      = createURI(NS+"imports");
    public static final Node startActs    = createURI(NS+"startActs");
    public static final Node start        = createURI(NS+"start");
    public static final Node shapes       = createURI(NS+"shapes");
    public static final Node abstract_    = createURI(NS+"abstract");     // "abstract" is a Java keyword
    public static final Node shapeExpr    = createURI(NS+"shapeExpr");
    public static final Node shapeExprs   = createURI(NS+"shapeExprs");
    public static final Node nodeKind     = createURI(NS+"nodeKind");
    public static final Node datatype     = createURI(NS+"datatype");
    public static final Node length       = createURI(NS+"length");
    public static final Node minlength    = createURI(NS+"minlength");
    public static final Node maxlength    = createURI(NS+"maxlength");
    public static final Node pattern      = createURI(NS+"pattern");
    public static final Node flags        = createURI(NS+"flags");
    public static final Node mininclusive = createURI(NS+"mininclusive");
    public static final Node minexclusive = createURI(NS+"minexclusive");
    public static final Node maxinclusive = createURI(NS+"maxinclusive");
    public static final Node maxexclusive = createURI(NS+"maxexclusive");
    public static final Node totaldigits    = createURI(NS+"totaldigits");
    public static final Node fractiondigits = createURI(NS+"fractiondigits");
    public static final Node values       = createURI(NS+"values");
    public static final Node semActs      = createURI(NS+"semActs");
    public static final Node annotation   = createURI(NS+"annotation");   // recognized, not built (no AST type)
    public static final Node extends_     = createURI(NS+"extends");      // "extends" is a Java keyword
    public static final Node closed       = createURI(NS+"closed");
    public static final Node extra        = createURI(NS+"extra");
    public static final Node expression   = createURI(NS+"expression");
    public static final Node name         = createURI(NS+"name");
    public static final Node code         = createURI(NS+"code");
    public static final Node predicate    = createURI(NS+"predicate");
    public static final Node object       = createURI(NS+"object");       // Annotation.object; unused (Annotation dropped)
    public static final Node stem         = createURI(NS+"stem");
    public static final Node exclusion    = createURI(NS+"exclusion");
    public static final Node languageTag  = createURI(NS+"languageTag");
    public static final Node min          = createURI(NS+"min");
    public static final Node max          = createURI(NS+"max");
    public static final Node expressions  = createURI(NS+"expressions");
    public static final Node inverse      = createURI(NS+"inverse");
    public static final Node negated      = createURI(NS+"negated");      // recognized, discarded (no AST field)
    public static final Node valueExpr    = createURI(NS+"valueExpr");

    // -- Fixed values of sx:nodeKind (not in the ShExR.shex grammar: sx:triple - kept for forward compatibility)

    public static final Node iri          = createURI(NS+"iri");
    public static final Node bnode        = createURI(NS+"bnode");
    public static final Node literal      = createURI(NS+"literal");
    public static final Node nonliteral   = createURI(NS+"nonliteral");
    public static final Node triple       = createURI(NS+"triple");
}
