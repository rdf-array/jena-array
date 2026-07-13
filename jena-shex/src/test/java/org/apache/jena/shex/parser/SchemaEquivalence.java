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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.jena.graph.Node;
import org.apache.jena.shex.ShapeDecl;
import org.apache.jena.shex.ShexSchema;
import org.apache.jena.shex.expressions.*;

/**
 * Semantic equivalence of two {@link ShexSchema}s, tolerant of two representation choices that
 * {@code ShexSchema.sameAs}/the AST's own {@code equals()} treat as structurally different even
 * though they describe the same schema (this is the same relaxation shex.js applies when
 * comparing schemas across ShExC/ShExJ/ShExR: see its schema-normalization step):
 * <ul>
 * <li>{@link ShapeExprRef}/{@link TripleExprRef} vs. the referenced declaration's body inlined
 *     directly - which occurrence of a shared shape/triple expression is "the definition" and
 *     which is "the reference" is a serialization choice, not part of the schema's meaning.
 *     Every reference is resolved (via the owning schema's own {@code ShapeDecl}/{@code
 *     tripleRefs} lookup) before comparison.</li>
 * <li>{@link ShapeAnd}/{@link ShapeOr} member order - AND/OR are commutative, so members are
 *     compared as a multiset (bipartite match), not positionally.</li>
 * </ul>
 * Everything else (predicates, cardinalities, node kinds, facets, value sets, EachOf/OneOf
 * member order, closed/extra, abstract) is compared structurally, delegating to each AST class's
 * own {@code equals()} where no reference can occur (there is nothing to resolve inside a
 * {@link NodeConstraint}).
 * <p>
 * Recursive/mutually-recursive shapes are handled correctly: a pair of AST nodes already being
 * compared higher up the same call chain is assumed equal when encountered again (a standard
 * co-inductive/bisimulation check), rather than being followed into infinite recursion.
 */
public class SchemaEquivalence {

    public static boolean equivalent(ShexSchema s1, ShexSchema s2) {
        if ( !Objects.equals(s1.getImports(), s2.getImports()) )
            return false;
        Map<Node, ShapeDecl> m1 = s1.getShapeMap();
        Map<Node, ShapeDecl> m2 = s2.getShapeMap();
        if ( !m1.keySet().equals(m2.keySet()) )
            return false;
        Ctx ctx = new Ctx(s1, s2);
        for ( Node label : m1.keySet() ) {
            ShapeDecl d1 = m1.get(label);
            ShapeDecl d2 = m2.get(label);
            if ( d1.isAbstract() != d2.isAbstract() )
                return false;
            if ( !shapeExprEquivalent(ctx, d1.getShapeExpr(), d2.getShapeExpr()) )
                return false;
        }
        return true;
    }

    /** The two schemas being compared, plus the AST-node pairs currently being compared (cycle guard). */
    private static class Ctx {
        final ShexSchema s1, s2;
        final Set<ObjPair> shapeVisiting = new HashSet<>();
        final Set<ObjPair> tripleVisiting = new HashSet<>();
        Ctx(ShexSchema s1, ShexSchema s2) { this.s1 = s1; this.s2 = s2; }
    }

    /** Identity-based (not {@code equals()}-based) pair, deliberately: it's the cycle guard for the equals() we're computing. */
    private static final class ObjPair {
        final Object a, b;
        ObjPair(Object a, Object b) { this.a = a; this.b = b; }
        @Override public int hashCode() { return System.identityHashCode(a) * 31 + System.identityHashCode(b); }
        @Override public boolean equals(Object obj) {
            return obj instanceof ObjPair p && a == p.a && b == p.b;
        }
    }

    // ---- ShapeExpr

    private static ShapeExpr resolveShapeExpr(ShexSchema schema, ShapeExpr e) {
        Set<Node> seen = new HashSet<>();
        while ( e instanceof ShapeExprRef ref ) {
            Node label = ref.getLabel();
            if ( !seen.add(label) )
                return e; // alias cycle (<A> @<B>, <B> @<A>) - give up resolving further
            ShapeDecl decl = schema.get(label);
            if ( decl == null )
                return e; // dangling/external reference
            e = decl.getShapeExpr();
        }
        return e;
    }

    private static boolean shapeExprEquivalent(Ctx ctx, ShapeExpr e1, ShapeExpr e2) {
        ShapeExpr r1 = resolveShapeExpr(ctx.s1, e1);
        ShapeExpr r2 = resolveShapeExpr(ctx.s2, e2);
        if ( r1 == null || r2 == null )
            return r1 == r2;
        ObjPair key = new ObjPair(r1, r2);
        if ( !ctx.shapeVisiting.add(key) )
            return true; // already being compared higher up this chain -> cycle, assume equal
        try {
            return shapeExprEquivalentResolved(ctx, r1, r2);
        } finally {
            ctx.shapeVisiting.remove(key);
        }
    }

    private static boolean shapeExprEquivalentResolved(Ctx ctx, ShapeExpr e1, ShapeExpr e2) {
        if ( e1.getClass() != e2.getClass() )
            return false;
        if ( e1 instanceof Shape sh1 && e2 instanceof Shape sh2 ) {
            return sh1.isClosed() == sh2.isClosed()
                && Objects.equals(sh1.getExtras(), sh2.getExtras())
                && shapeExprRefListEquivalent(ctx, sh1.getExtends(), sh2.getExtends())
                && tripleExprEquivalent(ctx, sh1.getTripleExpr(), sh2.getTripleExpr());
        }
        if ( e1 instanceof ShapeAnd a1 && e2 instanceof ShapeAnd a2 )
            return shapeExprMultisetEquivalent(ctx, a1.getShapeExprs(), a2.getShapeExprs(), ShapeAnd.class);
        if ( e1 instanceof ShapeOr o1 && e2 instanceof ShapeOr o2 )
            return shapeExprMultisetEquivalent(ctx, o1.getShapeExprs(), o2.getShapeExprs(), ShapeOr.class);
        if ( e1 instanceof ShapeNot n1 && e2 instanceof ShapeNot n2 )
            return shapeExprEquivalent(ctx, n1.getShapeExpr(), n2.getShapeExpr());
        if ( e1 instanceof NodeConstraint nc1 && e2 instanceof NodeConstraint nc2 )
            return nc1.equals(nc2); // no references possible inside a NodeConstraint
        if ( e1 instanceof ShapeExternal )
            return true; // getClass() check above already confirmed both are ShapeExternal
        if ( e1 instanceof ShapeExprRef r1 && e2 instanceof ShapeExprRef r2 )
            // Both dangling (resolveShapeExpr gave up on both) - compare what we can.
            return Objects.equals(r1.getLabel(), r2.getLabel());
        throw new IllegalStateException("Unhandled ShapeExpr type: "+e1.getClass());
    }

    private static boolean shapeExprRefListEquivalent(Ctx ctx, List<ShapeExprRef> l1, List<ShapeExprRef> l2) {
        if ( l1.size() != l2.size() )
            return false;
        for ( int i = 0; i < l1.size(); i++ )
            if ( !shapeExprEquivalent(ctx, l1.get(i), l2.get(i)) )
                return false;
        return true;
    }

    /**
     * AND/OR are commutative <em>and</em> associative: compare members as a multiset (bipartite
     * match) after flattening any directly-nested (not through a reference - a reference to a
     * named shape stays an indivisible unit) same-type ShapeAnd/ShapeOr, so {@code AND(AND(a,b),c)}
     * and {@code AND(a,b,c)} compare equal.
     */
    private static boolean shapeExprMultisetEquivalent(Ctx ctx, List<ShapeExpr> l1, List<ShapeExpr> l2,
                                                         Class<? extends ShapeExpr> assocType) {
        List<ShapeExpr> f1 = flatten(l1, assocType);
        List<ShapeExpr> f2 = flatten(l2, assocType);
        if ( f1.size() != f2.size() )
            return false;
        return matchAll(ctx, f1, f2, new boolean[f2.size()], 0);
    }

    private static List<ShapeExpr> flatten(List<ShapeExpr> members, Class<? extends ShapeExpr> assocType) {
        List<ShapeExpr> out = new ArrayList<>();
        for ( ShapeExpr m : members ) {
            if ( assocType.isInstance(m) ) {
                List<ShapeExpr> sub = ( m instanceof ShapeAnd sa ) ? sa.getShapeExprs() : ((ShapeOr)m).getShapeExprs();
                out.addAll(flatten(sub, assocType));
            } else {
                out.add(m);
            }
        }
        return out;
    }

    private static boolean matchAll(Ctx ctx, List<ShapeExpr> l1, List<ShapeExpr> l2, boolean[] used, int i) {
        if ( i == l1.size() )
            return true;
        for ( int j = 0; j < l2.size(); j++ ) {
            if ( used[j] )
                continue;
            used[j] = true;
            if ( shapeExprEquivalent(ctx, l1.get(i), l2.get(j)) && matchAll(ctx, l1, l2, used, i+1) )
                return true;
            used[j] = false;
        }
        return false;
    }

    // ---- TripleExpr

    private static TripleExpr resolveTripleExpr(ShexSchema schema, TripleExpr t) {
        Set<Node> seen = new HashSet<>();
        while ( t instanceof TripleExprRef ref ) {
            Node label = ref.getLabel();
            if ( !seen.add(label) )
                return t;
            TripleExpr real = schema.getTripleExpr(label);
            if ( real == null )
                return t; // dangling/external reference
            t = real;
        }
        return t;
    }

    private static boolean tripleExprEquivalent(Ctx ctx, TripleExpr t1, TripleExpr t2) {
        TripleExpr r1 = resolveTripleExpr(ctx.s1, t1);
        TripleExpr r2 = resolveTripleExpr(ctx.s2, t2);
        if ( r1 == null || r2 == null )
            return r1 == r2;
        ObjPair key = new ObjPair(r1, r2);
        if ( !ctx.tripleVisiting.add(key) )
            return true;
        try {
            return tripleExprEquivalentResolved(ctx, r1, r2);
        } finally {
            ctx.tripleVisiting.remove(key);
        }
    }

    private static boolean tripleExprEquivalentResolved(Ctx ctx, TripleExpr t1, TripleExpr t2) {
        if ( t1.getClass() != t2.getClass() )
            return false;
        if ( t1 instanceof TripleConstraint c1 && t2 instanceof TripleConstraint c2 ) {
            return Objects.equals(c1.getPredicate(), c2.getPredicate())
                && c1.isInverse() == c2.isInverse()
                && shapeExprEquivalent(ctx, c1.getValueExpr(), c2.getValueExpr());
        }
        if ( t1 instanceof EachOf e1 && t2 instanceof EachOf e2 )
            return tripleExprListEquivalent(ctx, e1.getTripleExprs(), e2.getTripleExprs());
        if ( t1 instanceof OneOf o1 && t2 instanceof OneOf o2 )
            return tripleExprListEquivalent(ctx, o1.getTripleExprs(), o2.getTripleExprs());
        if ( t1 instanceof TripleExprCardinality tc1 && t2 instanceof TripleExprCardinality tc2 ) {
            return Objects.equals(tc1.getCardinality(), tc2.getCardinality())
                && tripleExprEquivalent(ctx, tc1.getSubExpr(), tc2.getSubExpr());
        }
        if ( t1 instanceof TripleExprEmpty )
            return true; // getClass() check above already confirmed both are TripleExprEmpty
        if ( t1 instanceof TripleExprRef r1 && t2 instanceof TripleExprRef r2 )
            // Both dangling (resolveTripleExpr gave up on both) - compare what we can.
            return Objects.equals(r1.getLabel(), r2.getLabel());
        throw new IllegalStateException("Unhandled TripleExpr type: "+t1.getClass());
    }

    private static boolean tripleExprListEquivalent(Ctx ctx, List<TripleExpr> l1, List<TripleExpr> l2) {
        if ( l1.size() != l2.size() )
            return false;
        for ( int i = 0; i < l1.size(); i++ )
            if ( !tripleExprEquivalent(ctx, l1.get(i), l2.get(i)) )
                return false;
        return true;
    }
}
