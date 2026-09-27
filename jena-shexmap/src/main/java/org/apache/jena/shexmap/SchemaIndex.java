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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jena.graph.Node;
import org.apache.jena.shex.ShapeDecl;
import org.apache.jena.shex.ShexSchema;
import org.apache.jena.shex.expressions.Cardinality;
import org.apache.jena.shex.expressions.EachOf;
import org.apache.jena.shex.expressions.NodeConstraint;
import org.apache.jena.shex.expressions.NodeConstraintComponent;
import org.apache.jena.shex.expressions.OneOf;
import org.apache.jena.shex.expressions.SemAct;
import org.apache.jena.shex.expressions.Shape;
import org.apache.jena.shex.expressions.ShapeAnd;
import org.apache.jena.shex.expressions.ShapeExpr;
import org.apache.jena.shex.expressions.ShapeExprRef;
import org.apache.jena.shex.expressions.TripleConstraint;
import org.apache.jena.shex.expressions.TripleExpr;
import org.apache.jena.shex.expressions.TripleExprCardinality;
import org.apache.jena.shex.expressions.TripleExprRef;
import org.apache.jena.shex.expressions.ValueConstraint;
import org.apache.jena.shex.expressions.ValueSetItem;
import org.apache.jena.shex.expressions.ValueSetRange;
import org.apache.jena.shex.sys.SysShex;

/** A ShEx schema indexed the way ShExMap walks it: shapes by label, inclusions, extensions,
 * and the Map actions and cardinalities of triple constraints.
 * <p>
 * Jena attaches a cardinality to a triple expression by wrapping it in a
 * {@link TripleExprCardinality}; ShExMap treats the wrapper and what it wraps as one
 * expression (a repeated constraint, a repeated group).  {@link #inner} unwraps, {@link #asTc}
 * sees a triple constraint through its wrapper, and an expression's identity is its
 * {@code id} (see {@link #key}). */
public final class SchemaIndex {
    /** The Map extension's IRI, as the ShExMap examples write it. */
    public static final String MAP_EXTENSION = "http://shex.io/extensions/Map/#";

    private final ShexSchema schema;
    private final Map<String, String> prefixes;
    private final Map<Node, List<ShapeDecl>> children = new LinkedHashMap<>();

    public SchemaIndex(ShexSchema schema) {
        this.schema = schema;
        this.prefixes = new LinkedHashMap<>();
        if (schema.getPrefixMap() != null)
            schema.getPrefixMap().forEach(prefixes::put);
        for (ShapeDecl decl : schema.getShapes())
            for (Shape shape : shapesIn(decl.getShapeExpr()))
                for (ShapeExprRef base : shape.getExtends())
                    children.computeIfAbsent(base.getLabel(), k -> new ArrayList<>()).add(decl);
    }

    public ShexSchema schema() { return schema; }

    /** Prefix declarations for ShExMap variable names: alias to namespace IRI. */
    public Map<String, String> prefixes() { return prefixes; }

    /** The declaration for a shape label, or null. */
    public ShapeDecl declaration(Node label) {
        if (label == null)
            return null;
        return schema.hasShape(label) ? schema.get(label) : null;
    }

    /** The shape expression a start argument names: the schema's start when {@code start} is null
     * or the start node, else a reference to the label. */
    public ShapeExpr startExpr(Node start) {
        if (start == null || SysShex.startNode.equals(start)) {
            ShapeDecl s = schema.getStart();
            if (s == null)
                throw new ShExMapException("the schema has no start shape; name one");
            return s.getShapeExpr();
        }
        return ShapeExprRef.create(start);
    }

    /** A triple expression, an inclusion ({@code &label}) resolved to what it names. */
    public TripleExpr resolve(TripleExpr expr) {
        if (expr instanceof TripleExprRef ref) {
            TripleExpr target = schema.getTripleExpr(ref.getLabel());
            if (target == null)
                throw new ShExMapException("the triple expression inclusion " + labelText(ref.getLabel()) + " names no expression");
            return target;
        }
        return expr;
    }

    /** What satisfying {@code decl} can mean, most specific first: its non-abstract extensions
     * (deepest first), then the declaration itself unless it is abstract. */
    public List<ShapeDecl> extensionCandidates(ShapeDecl decl) {
        List<ShapeDecl> found = new ArrayList<>();
        descendants(decl.getLabel(), new HashSet<>(), found);
        java.util.Collections.reverse(found);
        if (!decl.isAbstract())
            found.add(decl);
        return found;
    }

    private void descendants(Node label, Set<Node> seen, List<ShapeDecl> out) {
        for (ShapeDecl d : children.getOrDefault(label, List.of())) {
            if (seen.add(d.getLabel())) {
                if (!d.isAbstract())
                    out.add(d);
                descendants(d.getLabel(), seen, out);
            }
        }
    }

    /** The shapes whose triple expressions share a node's neighbourhood when it matches
     * {@code shape}: those of everything {@code shape} EXTENDS (transitively), then {@code shape}. */
    public List<Shape> shapeParts(Shape shape) {
        List<Shape> parts = new ArrayList<>();
        partsInto(shape, new HashSet<>(), parts);
        return parts;
    }

    private void partsInto(Shape shape, Set<Node> seen, List<Shape> parts) {
        for (ShapeExprRef base : shape.getExtends()) {
            if (!seen.add(base.getLabel()))
                continue;
            ShapeDecl decl = declaration(base.getLabel());
            if (decl != null)
                for (Shape s : shapesIn(decl.getShapeExpr()))
                    partsInto(s, seen, parts);
        }
        parts.add(shape);
    }

    /** The shapes a shape expression is made of: a shape, or the shapes of a conjunction. */
    public static List<Shape> shapesIn(ShapeExpr se) {
        List<Shape> out = new ArrayList<>();
        if (se instanceof Shape s)
            out.add(s);
        else if (se instanceof ShapeAnd and)
            for (ShapeExpr part : and.getShapeExprs())
                out.addAll(shapesIn(part));
        return out;
    }

    // -- expressions ----------------------------------------------------------------------------

    /** A stable identity for an expression: its id, shared by every reference to the same object. */
    public static int key(org.apache.jena.shex.expressions.Expression e) {
        return e.id;
    }

    /** The expression under a cardinality wrapper, or the expression itself. */
    public static TripleExpr inner(TripleExpr e) {
        return e instanceof TripleExprCardinality c ? c.getSubExpr() : e;
    }

    /** {@code (min, max)} of an expression; {@link Cardinality#UNBOUNDED} for {@code *} and {@code +}. */
    public static Cardinality cardinality(TripleExpr e) {
        return e instanceof TripleExprCardinality c ? c.getCardinality() : Cardinality.ONE;
    }

    /** A repeated expression: one whose max is not exactly one ({@code *}, {@code +}, {@code {2,5}} ...). */
    public static boolean isRepeated(TripleExpr e) {
        return cardinality(e).max != 1;
    }

    /** Any cardinality but exactly one, as the materializer treats it ({@code ?} included). */
    public static boolean isRepetition(TripleExpr e) {
        Cardinality c = cardinality(e);
        return !(c.min == 1 && c.max == 1);
    }

    /** The sub-expressions of a group (through a cardinality wrapper), or none. */
    public static List<TripleExpr> subExpressions(TripleExpr e) {
        TripleExpr in = inner(e);
        if (in instanceof EachOf each)
            return each.getTripleExprs();
        if (in instanceof OneOf one)
            return one.getTripleExprs();
        return List.of();
    }

    /** Whether a semantic action's name is the Map extension (with or without the trailing {@code #}). */
    public static boolean isMapAction(SemAct act) {
        String iri = act.getIri();
        return iri != null && stripHash(iri).equals(stripHash(MAP_EXTENSION));
    }

    private static String stripHash(String s) {
        return s.endsWith("#") ? s.substring(0, s.length() - 1) : s;
    }

    /** The Map actions of an expression: those on it and, through a cardinality wrapper, on
     * what it wraps. */
    public static List<SemAct> mapActions(TripleExpr e) {
        List<SemAct> out = new ArrayList<>();
        collectMapActions(e.getSemActs(), out);
        if (e instanceof TripleExprCardinality c)
            collectMapActions(c.getSubExpr().getSemActs(), out);
        return out;
    }

    private static void collectMapActions(Collection<SemAct> acts, List<SemAct> out) {
        if (acts != null)
            for (SemAct a : acts)
                if (isMapAction(a))
                    out.add(a);
    }

    public static String codeOf(SemAct act) {
        return act.getCode() == null ? "" : act.getCode();
    }

    /** A triple constraint seen through its cardinality wrapper, if any. */
    public record Tc(TripleConstraint tc, TripleExpr expr) {
        /** The triple constraint of an expression, or null when it is a group or an inclusion. */
        public static Tc of(TripleExpr e) {
            return inner(e) instanceof TripleConstraint tc ? new Tc(tc, e) : null;
        }

        public Node predicate() { return tc.getPredicate(); }
        public boolean inverse() { return tc.isInverse(); }
        public ShapeExpr valueExpr() { return tc.getValueExpr(); }
        public int min() { return cardinality(expr).min; }
        public int max() { return cardinality(expr).max; }
        public List<SemAct> mapActions() { return SchemaIndex.mapActions(expr); }

        /** Whether the value expression can bind anything: anything but a node constraint. */
        public boolean referencesShape() { return SchemaIndex.referencesShape(tc.getValueExpr()); }

        public String predicateStr() {
            return (tc.isInverse() ? "^" : "") + tc.getPredicate().getURI();
        }
    }

    /** Whether a value expression can bind anything: anything but a node constraint. */
    public static boolean referencesShape(ShapeExpr ve) {
        return ve != null && !(ve instanceof NodeConstraint);
    }

    /** The triple constraints of a shape's own expression (not of the shapes nested in them). */
    public List<Tc> ownTcs(Shape shape) {
        List<Tc> out = new ArrayList<>();
        if (shape.getTripleExpr() != null)
            ownTcsInto(shape.getTripleExpr(), out);
        return out;
    }

    private void ownTcsInto(TripleExpr e, List<Tc> out) {
        e = resolve(e);
        Tc tc = Tc.of(e);
        if (tc != null)
            out.add(tc);
        else
            for (TripleExpr sub : subExpressions(e))
                ownTcsInto(sub, out);
    }

    /** The repeated constraints and groups directly under {@code exprs} (not inside another
     * repeated expression), in schema order: one list each in a scope's tree. */
    public List<TripleExpr> repeatedExpressions(List<TripleExpr> exprs) {
        List<TripleExpr> out = new ArrayList<>();
        for (TripleExpr e : exprs)
            repeatedInto(e, out);
        return out;
    }

    private void repeatedInto(TripleExpr e, List<TripleExpr> out) {
        e = resolve(e);
        if (isRepeated(e))
            out.add(e);
        else
            for (TripleExpr sub : subExpressions(e))
                repeatedInto(sub, out);
    }

    /** The one value a node constraint names ({@code [ex:a]} or {@code ["x"]}), if any. */
    public static Node singleValue(ShapeExpr ve) {
        if (!(ve instanceof NodeConstraint nc))
            return null;
        List<NodeConstraintComponent> comps = nc.getComponents();
        if (comps.size() != 1 || !(comps.get(0) instanceof ValueConstraint vc))
            return null;
        List<ValueSetRange> ranges = vc.getValueSetRanges();
        if (ranges.size() != 1 || ranges.get(0).numExclusions() != 0)
            return null;
        ValueSetItem item = ranges.get(0).item();
        if (item.isStem())
            return null;
        if (item.getIriStr() != null)
            return org.apache.jena.graph.NodeFactory.createURI(item.getIriStr());
        if (item.getLiteral() != null)
            return item.getLiteral();
        return null; // a language does not name one value
    }

    /** A short name for a triple expression: its first predicate(s). */
    public String labelOf(TripleExpr expr) {
        List<String> found = new ArrayList<>();
        List<TripleExpr> stack = new ArrayList<>();
        stack.add(expr);
        while (!stack.isEmpty() && found.size() < 2) {
            TripleExpr e = resolve(stack.remove(stack.size() - 1));
            Tc tc = Tc.of(e);
            if (tc != null) {
                found.add(tc.predicateStr());
            } else {
                List<TripleExpr> subs = subExpressions(e);
                for (int i = subs.size() - 1; i >= 0; i--)
                    stack.add(subs.get(i));
            }
        }
        if (found.isEmpty())
            return "(group)";
        if (found.size() == 1)
            return found.get(0);
        return "(" + String.join(", ", found) + ")";
    }

    /** A shape label for messages: {@code <iri>}, {@code _:label} or {@code START}. */
    public static String labelText(Node label) {
        if (label == null || SysShex.startNode.equals(label))
            return "START";
        if (label.isBlank())
            return "_:" + label.getBlankNodeLabel();
        return "<" + label.getURI() + ">";
    }

    /** The name of a shape expression's kind, for messages. */
    public static String kindOf(ShapeExpr se) {
        return se == null ? "nothing" : se.getClass().getSimpleName();
    }
}
