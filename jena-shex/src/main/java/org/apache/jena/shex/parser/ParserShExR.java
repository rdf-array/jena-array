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

package org.apache.jena.shex.parser;

import static org.apache.jena.system.G.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.riot.system.PrefixMapFactory;
import org.apache.jena.shex.ShapeDecl;
import org.apache.jena.shex.ShexSchema;
import org.apache.jena.shex.expressions.*;
import org.apache.jena.shex.sys.SysShex;
import org.apache.jena.shex.sys.VocabShExR;
import org.apache.jena.sparql.graph.NodeConst;

/**
 * Parser for ShExR : the RDF syntax for ShEx.
 * <p>
 * Builds the same AST ({@link ShexSchema}, {@link ShapeExpr}, {@link TripleExpr}) as
 * {@link ParserShExC} (compact syntax) and the ShExJ (JSON) deserializer, by walking an RDF
 * graph directly with {@code graph.find}/{@link org.apache.jena.system.G} helpers, in the
 * style of {@code org.apache.jena.shacl.parser.ShapesParser}, rather than any generic
 * RDF-to-object binding.
 * <p>
 * Two ShExR features have no representation in this AST and are intentionally dropped, matching
 * what the ShExC parser already does (ShExC's own grammar parses and discards annotations, and
 * has no surface syntax for negated triple constraints at all):
 * <ul>
 * <li>{@code sx:annotation} - never read; no {@code Annotation} AST type exists.</li>
 * <li>{@code sx:negated} - read only to be logged and discarded; {@link TripleConstraint} has no field for it.</li>
 * </ul>
 */
public class ParserShExR {

    /** Per-parse recursion state : memoization for shape decls, memoization and a cycle guard for triple expressions. */
    private static class ParseState {
        final Graph graph;
        // Nodes appearing in the schema's top-level sx:shapes list, pre-scanned before any
        // shape body is parsed. A shapeDeclOrExpr position resolves to a ShapeExprRef (never an
        // inline body) whenever it names one of these nodes - including forward references to a
        // shape listed later in sx:shapes - or a sx:ShapeDecl-typed node anywhere.
        final Set<Node> topLevelLabels = new HashSet<>();
        // ShapeDecl nodes, keyed by their RDF node, once parsed. Only ever populated by the
        // top-level sx:shapes list walk (handleTopLevelShapeDecl) - a shapeDeclOrExpr reference
        // never recurses into the referenced declaration's body, it just builds a ShapeExprRef by
        // label, the same way ShExC's own shape references never re-parse the referenced shape.
        // This is what makes recursive/mutually-recursive shapes resolve without infinite recursion.
        final Map<Node, ShapeDecl> shapeDecls = new LinkedHashMap<>();
        // TripleExpr nodes, keyed by their RDF node, the first time each is fully parsed.
        // Becomes the "tripleRefs" map of the ShexSchema.
        final Map<Node, TripleExpr> tripleExprsDone = new LinkedHashMap<>();
        final Set<Node> tripleExprsInProgress = new HashSet<>();

        ParseState(Graph graph) { this.graph = graph; }
    }

    /** Parse a ShExR graph, locating its (single) {@code sx:Schema} node automatically. */
    public static ShexSchema parse(Graph graph, String sourceURI, String baseURI) {
        List<Node> schemaNodes = nodesOfTypeAsList(graph, VocabShExR.Schema);
        if ( schemaNodes.isEmpty() )
            throw new ShexParseException("No sx:Schema node found in ShExR graph");
        if ( schemaNodes.size() > 1 )
            throw new ShexParseException("Multiple sx:Schema nodes found in ShExR graph");
        return parse(graph, schemaNodes.get(0), sourceURI, baseURI);
    }

    /** Parse a ShExR graph, given the RDF node that is {@code a sx:Schema}. */
    public static ShexSchema parse(Graph graph, Node schemaNode, String sourceURI, String baseURI) {
        ParseState st = new ParseState(graph);
        return handleSchema(st, schemaNode, sourceURI, baseURI);
    }

    // ---- Schema

    private static ShexSchema handleSchema(ParseState st, Node schemaNode, String sourceURI, String baseURI) {
        Graph graph = st.graph;

        // null, not List.of(), when absent - matches ParserShExC's default (imports == null),
        // which ShexSchema.sameAs()/equals() distinguish from an empty list.
        List<String> imports = null;
        Node importsHead = getZeroOrOneSP(graph, schemaNode, VocabShExR.imports);
        if ( importsHead != null ) {
            imports = new ArrayList<>();
            for ( Node n : rdfList(graph, importsHead) )
                imports.add(n.getURI());
        }

        List<SemAct> startActs = handleSemActsIfPresent(graph, schemaNode, VocabShExR.startActs);

        List<Node> shapeDeclNodes = List.of();
        Node shapesHead = getZeroOrOneSP(graph, schemaNode, VocabShExR.shapes);
        if ( shapesHead != null )
            shapeDeclNodes = rdfList(graph, shapesHead);
        // Pre-scan before parsing any body, so forward references within one shape's body to a
        // shape listed later in sx:shapes still resolve to a ShapeExprRef, not an inline copy.
        st.topLevelLabels.addAll(shapeDeclNodes);

        List<ShapeDecl> shapes = new ArrayList<>();
        for ( Node declNode : shapeDeclNodes )
            shapes.add(handleTopLevelShapeDecl(st, declNode));

        ShapeDecl startDecl = null;
        Node startNode = getZeroOrOneSP(graph, schemaNode, VocabShExR.start);
        if ( startNode != null ) {
            ShapeExpr startExpr = resolveShapeDeclOrExpr(st, startNode);
            startDecl = new ShapeDecl(SysShex.startNode, false, startExpr);
            shapes.add(startDecl);
        }

        PrefixMap prefixes = PrefixMapFactory.create(graph.getPrefixMapping());
        return ShexSchema.shapes(sourceURI, baseURI, prefixes, startDecl, shapes, imports, startActs, st.tripleExprsDone);
    }

    // ---- ShapeDecl / shapeDeclOrExpr

    /**
     * Parse a member of the schema's top-level {@code sx:shapes} list. Usually this is a proper
     * {@code sx:ShapeDecl} node (label + optional {@code sx:abstract} + {@code sx:shapeExpr}
     * body), but some ShExR producers omit the wrapper for shapes that need no extra metadata:
     * the node then directly carries one of the 6 shapeExpr types and serves as both its own
     * label and its own body.
     */
    private static ShapeDecl handleTopLevelShapeDecl(ParseState st, Node declNode) {
        ShapeDecl existing = st.shapeDecls.get(declNode);
        if ( existing != null )
            return existing;
        ShapeDecl decl;
        if ( hasType(st.graph, declNode, VocabShExR.ShapeDecl) ) {
            Node abstractNode = getZeroOrOneSP(st.graph, declNode, VocabShExR.abstract_);
            boolean bstract = NodeConst.nodeTrue.equals(abstractNode);
            Node bodyNode = getOneSP(st.graph, declNode, VocabShExR.shapeExpr);
            decl = new ShapeDecl(declNode, bstract, handleShapeExprBody(st, bodyNode));
        } else {
            decl = new ShapeDecl(declNode, false, handleShapeExprBody(st, declNode));
        }
        st.shapeDecls.put(declNode, decl);
        return decl;
    }

    /**
     * Resolve a {@code shapeDeclOrExpr} position: a reference to a named shape is represented in
     * ShExR purely by node-identity sharing (the use site's object is the same node used
     * elsewhere as a {@code sx:ShapeDecl} subject, or directly as a top-level {@code sx:shapes}
     * member). Such a reference resolves to a {@link ShapeExprRef} by label only, without
     * recursing into the referenced declaration's body - exactly as ShExC's own shape references
     * (<code>@&lt;label&gt;</code>) never re-parse the referenced shape. Declaration bodies are
     * only ever parsed by the top-level {@code sx:shapes} list walk ({@link #handleTopLevelShapeDecl});
     * this is what makes recursive/mutually-recursive shapes resolve without infinite recursion.
     * Anything else is an inline shape expression body.
     */
    private static ShapeExpr resolveShapeDeclOrExpr(ParseState st, Node n) {
        if ( hasType(st.graph, n, VocabShExR.ShapeDecl) || st.topLevelLabels.contains(n) )
            return ShapeExprRef.create(n);
        return handleShapeExprBody(st, n);
    }

    private static ShapeExpr handleShapeExprBody(ParseState st, Node n) {
        if ( hasType(st.graph, n, VocabShExR.ShapeOr) )        return handleShapeOr(st, n);
        if ( hasType(st.graph, n, VocabShExR.ShapeAnd) )       return handleShapeAnd(st, n);
        if ( hasType(st.graph, n, VocabShExR.ShapeNot) )       return handleShapeNot(st, n);
        if ( hasType(st.graph, n, VocabShExR.NodeConstraint) ) return handleNodeConstraint(st, n);
        if ( hasType(st.graph, n, VocabShExR.Shape) )          return handleShape(st, n);
        if ( hasType(st.graph, n, VocabShExR.ShapeExternal) )  return new ShapeExternal();
        // Dangling/external reference: no recognized type. Tolerate, as ShExC tolerates
        // undefined shape references at parse time.
        return ShapeExprRef.create(n);
    }

    private static ShapeExpr handleShapeOr(ParseState st, Node n) {
        return ShapeOr.create(shapeExprsMembers(st, n));
    }

    private static ShapeExpr handleShapeAnd(ParseState st, Node n) {
        return ShapeAnd.create(shapeExprsMembers(st, n));
    }

    private static List<ShapeExpr> shapeExprsMembers(ParseState st, Node n) {
        Node head = getOneSP(st.graph, n, VocabShExR.shapeExprs);
        List<ShapeExpr> members = new ArrayList<>();
        for ( Node m : rdfList(st.graph, head) )
            members.add(resolveShapeDeclOrExpr(st, m));
        return members;
    }

    private static ShapeExpr handleShapeNot(ParseState st, Node n) {
        Node bodyNode = getOneSP(st.graph, n, VocabShExR.shapeExpr);
        return ShapeNot.create(resolveShapeDeclOrExpr(st, bodyNode));
    }

    private static ShapeExpr handleShape(ParseState st, Node n) {
        Graph graph = st.graph;
        Shape.Builder b = Shape.newBuilder();

        Node extendsHead = getZeroOrOneSP(graph, n, VocabShExR.extends_);
        if ( extendsHead != null ) {
            List<ShapeExprRef> xtends = new ArrayList<>();
            for ( Node m : rdfList(graph, extendsHead) ) {
                ShapeExpr resolved = resolveShapeDeclOrExpr(st, m);
                if ( !(resolved instanceof ShapeExprRef) )
                    throw new ShexParseException("sx:extends member is not a shape reference: "+m);
                xtends.add((ShapeExprRef)resolved);
            }
            b.xtends(xtends);
        }

        Node closedNode = getZeroOrOneSP(graph, n, VocabShExR.closed);
        b.closed(NodeConst.nodeTrue.equals(closedNode));

        List<Node> extraNodes = listSP(graph, n, VocabShExR.extra);
        if ( !extraNodes.isEmpty() )
            b.extras(extraNodes);

        // No sx:expression means "no triple expression" (ShExC's "{}"), not "no constraint" -
        // matches ParserShExC.finishShapeDefinition's TripleExprEmpty.get() default.
        Node exprNode = getZeroOrOneSP(graph, n, VocabShExR.expression);
        TripleExpr tripleExpr = ( exprNode == null ) ? TripleExprEmpty.get() : resolveTripleExprOrRef(st, exprNode);
        b.shapeExpr(tripleExpr);

        b.semActs(handleSemActsIfPresent(graph, n, VocabShExR.semActs));

        return b.build();
    }

    // ---- TripleExpr

    /**
     * Resolve a triple-expression position. Unlike shapeDeclOrExpr, ShExR has no wrapper type
     * for triple expressions, so the first time a node is reached it is parsed inline; any later
     * encounter of the same node returns a {@link TripleExprRef}. A node with none of the three
     * triple-expression types is the grammar's "NotYetResolvedInclusion" case: a pure
     * forward/external reference with nothing to parse.
     */
    private static TripleExpr resolveTripleExprOrRef(ParseState st, Node n) {
        TripleExpr done = st.tripleExprsDone.get(n);
        if ( done != null )
            return TripleExprRef.create(n);
        if ( st.tripleExprsInProgress.contains(n) )
            throw new ShexParseException("Cyclic tripleExpression: "+n);
        if ( !isTripleExprNode(st, n) )
            return TripleExprRef.create(n);
        st.tripleExprsInProgress.add(n);
        TripleExpr result = handleTripleExprBody(st, n);
        st.tripleExprsInProgress.remove(n);
        st.tripleExprsDone.put(n, result);
        return result;
    }

    private static boolean isTripleExprNode(ParseState st, Node n) {
        return hasType(st.graph, n, VocabShExR.TripleConstraint)
            || hasType(st.graph, n, VocabShExR.OneOf)
            || hasType(st.graph, n, VocabShExR.EachOf);
    }

    private static TripleExpr handleTripleExprBody(ParseState st, Node n) {
        if ( hasType(st.graph, n, VocabShExR.TripleConstraint) ) return handleTripleConstraint(st, n);
        if ( hasType(st.graph, n, VocabShExR.OneOf) )            return handleOneOfOrEachOf(st, n, true);
        if ( hasType(st.graph, n, VocabShExR.EachOf) )           return handleOneOfOrEachOf(st, n, false);
        throw new ShexParseException("Unrecognized triple expression: "+n);
    }

    private static TripleExpr handleTripleConstraint(ParseState st, Node n) {
        Graph graph = st.graph;
        Node predicateNode = getOneSP(graph, n, VocabShExR.predicate);
        Node inverseNode = getZeroOrOneSP(graph, n, VocabShExR.inverse);
        boolean inverse = NodeConst.nodeTrue.equals(inverseNode);

        Node negatedNode = getZeroOrOneSP(graph, n, VocabShExR.negated);
        if ( NodeConst.nodeTrue.equals(negatedNode) )
            SysShex.log.warn("sx:negated is not supported by this AST; ignoring on {}", n);

        // No sx:valueExpr means "any node" (ShExC's "." atom), which ParserShExC represents as
        // an empty NodeConstraint (ParserShExC.shapeAtomDOT), not null.
        Node valueExprNode = getZeroOrOneSP(graph, n, VocabShExR.valueExpr);
        ShapeExpr valueExpr = ( valueExprNode == null )
            ? NodeConstraint.create(List.of(), null)
            : resolveShapeDeclOrExpr(st, valueExprNode);

        List<SemAct> semActs = handleSemActsIfPresent(graph, n, VocabShExR.semActs);
        TripleExpr core = TripleConstraint.create(n, predicateNode, inverse, valueExpr, semActs);
        return wrapCardinality(graph, n, core);
    }

    private static TripleExpr handleOneOfOrEachOf(ParseState st, Node n, boolean isOneOf) {
        Graph graph = st.graph;
        Node head = getOneSP(graph, n, VocabShExR.expressions);
        List<TripleExpr> members = new ArrayList<>();
        for ( Node m : rdfList(graph, head) )
            members.add(resolveTripleExprOrRef(st, m));
        List<SemAct> semActs = handleSemActsIfPresent(graph, n, VocabShExR.semActs);
        TripleExpr core = isOneOf ? OneOf.create(members, semActs) : EachOf.create(members, semActs);
        return wrapCardinality(graph, n, core);
    }

    /** {@code sx:min}/{@code sx:max} : optional, {@code -1} for unbounded max. Wrapper carries no semActs of its own. */
    private static TripleExpr wrapCardinality(Graph graph, Node n, TripleExpr core) {
        Node minN = getZeroOrOneSP(graph, n, VocabShExR.min);
        Node maxN = getZeroOrOneSP(graph, n, VocabShExR.max);
        if ( minN == null && maxN == null )
            return core;
        int min = ( minN == null ) ? 1 : Integer.parseInt(minN.getLiteralLexicalForm());
        int max = ( maxN == null ) ? 1 : parseMax(maxN);
        return TripleExprCardinality.create(core, new Cardinality(min, max), null);
    }

    private static int parseMax(Node maxN) {
        int v = Integer.parseInt(maxN.getLiteralLexicalForm());
        return v < 0 ? Cardinality.UNBOUNDED : v;
    }

    // ---- NodeConstraint

    private static ShapeExpr handleNodeConstraint(ParseState st, Node n) {
        Graph graph = st.graph;
        List<NodeConstraintComponent> comps = new ArrayList<>();

        Node nk = getZeroOrOneSP(graph, n, VocabShExR.nodeKind);
        if ( nk != null )
            comps.add(new NodeKindConstraint(nodeKindOf(nk)));

        Node dt = getZeroOrOneSP(graph, n, VocabShExR.datatype);
        if ( dt != null )
            comps.add(new DatatypeConstraint(dt));

        addStrLength(comps, graph, n, VocabShExR.length,    StrLengthKind.LENGTH);
        addStrLength(comps, graph, n, VocabShExR.minlength, StrLengthKind.MINLENGTH);
        addStrLength(comps, graph, n, VocabShExR.maxlength, StrLengthKind.MAXLENGTH);

        Node patNode = getZeroOrOneSP(graph, n, VocabShExR.pattern);
        if ( patNode != null ) {
            Node flagsNode = getZeroOrOneSP(graph, n, VocabShExR.flags);
            // sx:pattern preserves ShExC's raw regex escape syntax (\t, \-, \\, ...), not a fully
            // resolved string (Unicode escapes are already resolved by the Turtle parser itself,
            // unlike ShExC's own two-pass unescapeUnicode+unescapeShexRegex) - apply the same
            // Shex-regex-escape pass ParserShExC.stringFacetRegex applies, so both parsers compute
            // the same final pattern text.
            String pattern = ShexParserLib.unescapeShexRegex(patNode.getLiteralLexicalForm(), '\\', false);
            comps.add(new StrRegexConstraint(pattern, flagsNode == null ? null : flagsNode.getLiteralLexicalForm()));
        }

        addNumRange(comps, graph, n, VocabShExR.mininclusive, NumRangeKind.MININCLUSIVE);
        addNumRange(comps, graph, n, VocabShExR.minexclusive, NumRangeKind.MINEXCLUSIVE);
        addNumRange(comps, graph, n, VocabShExR.maxinclusive, NumRangeKind.MAXINCLUSIVE);
        addNumRange(comps, graph, n, VocabShExR.maxexclusive, NumRangeKind.MAXEXCLUSIVE);
        addNumLength(comps, graph, n, VocabShExR.totaldigits,    NumLengthKind.TOTALDIGITS);
        addNumLength(comps, graph, n, VocabShExR.fractiondigits, NumLengthKind.FRACTIONDIGITS);

        Node valuesHead = getZeroOrOneSP(graph, n, VocabShExR.values);
        if ( valuesHead != null ) {
            List<ValueSetRange> ranges = new ArrayList<>();
            for ( Node v : rdfList(graph, valuesHead) )
                ranges.add(handleValueSetValue(graph, v));
            comps.add(new ValueConstraint(ranges));
        }

        List<SemAct> semActs = handleSemActsIfPresent(graph, n, VocabShExR.semActs);
        return NodeConstraint.create(comps, semActs);
    }

    private static NodeKind nodeKindOf(Node nk) {
        if ( VocabShExR.iri.equals(nk) )        return NodeKind.IRI;
        if ( VocabShExR.bnode.equals(nk) )      return NodeKind.BNODE;
        if ( VocabShExR.literal.equals(nk) )    return NodeKind.LITERAL;
        if ( VocabShExR.nonliteral.equals(nk) ) return NodeKind.NONLITERAL;
        if ( VocabShExR.triple.equals(nk) )     return NodeKind.TRIPLE;
        throw new ShexParseException("Unrecognized sx:nodeKind value: "+nk);
    }

    private static void addStrLength(List<NodeConstraintComponent> comps, Graph graph, Node n, Node predicate, StrLengthKind kind) {
        Node v = getZeroOrOneSP(graph, n, predicate);
        if ( v != null )
            comps.add(StrLengthConstraint.create(kind, Integer.parseInt(v.getLiteralLexicalForm())));
    }

    private static void addNumLength(List<NodeConstraintComponent> comps, Graph graph, Node n, Node predicate, NumLengthKind kind) {
        Node v = getZeroOrOneSP(graph, n, predicate);
        if ( v != null )
            comps.add(new NumLengthConstraint(kind, Integer.parseInt(v.getLiteralLexicalForm())));
    }

    private static void addNumRange(List<NodeConstraintComponent> comps, Graph graph, Node n, Node predicate, NumRangeKind kind) {
        Node v = getZeroOrOneSP(graph, n, predicate);
        if ( v != null )
            comps.add(new NumRangeConstraint(kind, v));
    }

    // ---- Value set values
    // No IriStem/IriStemRange/LiteralStem/LiteralStemRange/Language/LanguageStem/LanguageStemRange/
    // Wildcard AST classes exist; all collapse onto ValueSetItem/ValueSetRange, matching
    // ParserShExC's own value-set construction (startValueSetValueDot/valueSetIriRange/etc).
    // "Dot"/wildcard is ValueSetRange(null,null,null,false).

    private static ValueSetRange handleValueSetValue(Graph graph, Node n) {
        if ( hasType(graph, n, VocabShExR.IriStem) ) {
            String stem = getOneSP(graph, n, VocabShExR.stem).getLiteralLexicalForm();
            return new ValueSetRange(stem, null, null, true);
        }
        if ( hasType(graph, n, VocabShExR.IriStemRange) ) {
            Node stemNode = getOneSP(graph, n, VocabShExR.stem);
            ValueSetRange range = isWildcard(graph, stemNode)
                ? new ValueSetRange(null, null, null, false)
                : new ValueSetRange(stemNode.getLiteralLexicalForm(), null, null, true);
            range.setExclusions(iriExclusions(graph, n));
            return range;
        }
        if ( hasType(graph, n, VocabShExR.LiteralStem) ) {
            Node stem = getOneSP(graph, n, VocabShExR.stem);
            return new ValueSetRange(null, null, stem, true);
        }
        if ( hasType(graph, n, VocabShExR.LiteralStemRange) ) {
            Node stemNode = getOneSP(graph, n, VocabShExR.stem);
            ValueSetRange range = isWildcard(graph, stemNode)
                ? new ValueSetRange(null, null, null, false)
                : new ValueSetRange(null, null, stemNode, true);
            range.setExclusions(literalExclusions(graph, n));
            return range;
        }
        if ( hasType(graph, n, VocabShExR.Language) ) {
            String tag = getOneSP(graph, n, VocabShExR.languageTag).getLiteralLexicalForm();
            return new ValueSetRange(null, tag, null, false);
        }
        if ( hasType(graph, n, VocabShExR.LanguageStem) ) {
            String stem = getOneSP(graph, n, VocabShExR.stem).getLiteralLexicalForm();
            return new ValueSetRange(null, stem, null, true);
        }
        if ( hasType(graph, n, VocabShExR.LanguageStemRange) ) {
            Node stemNode = getOneSP(graph, n, VocabShExR.stem);
            ValueSetRange range = isWildcard(graph, stemNode)
                ? new ValueSetRange(null, null, null, false)
                : new ValueSetRange(null, stemNode.getLiteralLexicalForm(), null, true);
            range.setExclusions(languageExclusions(graph, n));
            return range;
        }
        // objectValue : bare IRI or bare Literal, no rdf:type triple.
        if ( n.isURI() )
            return new ValueSetRange(n.getURI(), null, null, false);
        if ( n.isLiteral() )
            return new ValueSetRange(null, null, n, false);
        throw new ShexParseException("Unrecognized value set value: "+n);
    }

    private static boolean isWildcard(Graph graph, Node stemNode) {
        return hasType(graph, stemNode, VocabShExR.Wildcard);
    }

    private static List<ValueSetItem> iriExclusions(Graph graph, Node n) {
        List<ValueSetItem> result = new ArrayList<>();
        for ( Node ex : rdfList(graph, getOneSP(graph, n, VocabShExR.exclusion)) ) {
            if ( hasType(graph, ex, VocabShExR.IriStem) ) {
                String stem = getOneSP(graph, ex, VocabShExR.stem).getLiteralLexicalForm();
                result.add(new ValueSetItem(stem, null, null, true));
            } else {
                result.add(new ValueSetItem(ex.getURI(), null, null, false));
            }
        }
        return result;
    }

    private static List<ValueSetItem> literalExclusions(Graph graph, Node n) {
        List<ValueSetItem> result = new ArrayList<>();
        for ( Node ex : rdfList(graph, getOneSP(graph, n, VocabShExR.exclusion)) ) {
            if ( hasType(graph, ex, VocabShExR.LiteralStem) ) {
                Node stem = getOneSP(graph, ex, VocabShExR.stem);
                result.add(new ValueSetItem(null, null, stem, true));
            } else {
                result.add(new ValueSetItem(null, null, ex, false));
            }
        }
        return result;
    }

    private static List<ValueSetItem> languageExclusions(Graph graph, Node n) {
        List<ValueSetItem> result = new ArrayList<>();
        for ( Node ex : rdfList(graph, getOneSP(graph, n, VocabShExR.exclusion)) ) {
            if ( hasType(graph, ex, VocabShExR.LanguageStem) ) {
                String stem = getOneSP(graph, ex, VocabShExR.stem).getLiteralLexicalForm();
                result.add(new ValueSetItem(null, stem, null, true));
            } else {
                result.add(new ValueSetItem(null, ex.getLiteralLexicalForm(), null, false));
            }
        }
        return result;
    }

    // ---- SemAct

    private static SemAct handleSemAct(Graph graph, Node n) {
        Node nameNode = getOneSP(graph, n, VocabShExR.name);
        Node codeNode = getZeroOrOneSP(graph, n, VocabShExR.code);
        String code = ( codeNode == null ) ? null : codeNode.getLiteralLexicalForm();
        return new SemAct(nameNode.getURI(), code);
    }

    private static List<SemAct> handleSemActsIfPresent(Graph graph, Node subject, Node predicate) {
        Node head = getZeroOrOneSP(graph, subject, predicate);
        if ( head == null )
            return null;
        List<SemAct> result = new ArrayList<>();
        for ( Node actNode : rdfList(graph, head) )
            result.add(handleSemAct(graph, actNode));
        return result;
    }
}
