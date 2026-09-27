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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.jena.shex.ShapeDecl;
import org.apache.jena.shex.ShexSchema;
import org.apache.jena.shex.expressions.NodeConstraint;
import org.apache.jena.shex.expressions.SemAct;
import org.apache.jena.shex.expressions.Shape;
import org.apache.jena.shex.expressions.ShapeAnd;
import org.apache.jena.shex.expressions.ShapeExpr;
import org.apache.jena.shex.expressions.ShapeExprRef;
import org.apache.jena.shex.expressions.ShapeOr;
import org.apache.jena.shex.expressions.TripleExpr;
import org.apache.jena.graph.Node;
import org.apache.jena.shexmap.SchemaIndex.Tc;

/** Static checks of a ShExMap schema pair, before any data.
 * <p>
 * Given the input schema and the output schema, {@link #analyse} says whether every output
 * repetition has a well-formed iteration scope, whether every variable the output reads is bound in
 * the input where it can be read from, and which bindings go unused -- the questions
 * materialization would otherwise answer at run time, one graph at a time.
 * <p>
 * A variable's <em>site</em> is the chain of repeated constraints and groups of the input schema
 * that enclose its {@code %Map} code: it is bound once per iteration of the innermost one, or once
 * for the whole tree when the chain is empty.  An output repetition's <em>iteration scope</em> is
 * the deepest site among the variables its body reads, nested repetitions counting through their
 * parent site; it is ill-formed when two of those sites lie in unrelated lists.  A constraint may
 * read a variable whose site is an ancestor of (or equal to) the scope it is evaluated at; a
 * deeper site means "which one?", and an unrelated one means the value is not in scope. */
public final class Analysis {
    private Analysis() {}

    /** What {@link #analyse} found.  Errors make the pair not mappable; warnings do not.  Messages
     * write IRIs with the schemas' prefixes. */
    public static final class Report {
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        /** Input variable to site (the ids of the enclosing repeated expressions, outermost first). */
        public final Map<String, List<Integer>> bound = new LinkedHashMap<>();
        /** Variables the output reads. */
        public final Set<String> read = new LinkedHashSet<>();
        /** Repeated expression id to name. */
        public final Map<Integer, String> labels = new HashMap<>();
        public final Map<String, String> prefixes = new LinkedHashMap<>();

        public boolean ok() { return errors.isEmpty(); }

        public String where(List<Integer> site) {
            if (site.isEmpty())
                return "the root";
            List<String> names = new ArrayList<>();
            for (int k : site)
                names.add(labels.getOrDefault(k, "?"));
            return "each " + String.join(" / ", names);
        }

        /** {@code text} with every namespace the schemas declare written as its prefix. */
        public String shortened(String text) {
            List<Map.Entry<String, String>> entries = new ArrayList<>(prefixes.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue().length(), a.getValue().length()));
            for (Map.Entry<String, String> e : entries)
                if (!e.getValue().isEmpty())
                    text = text.replace(e.getValue(), e.getKey() + ":");
            return text;
        }

        void error(String message) { errors.add(shortened(message)); }
        void warn(String message) { warnings.add(shortened(message)); }

        @Override public String toString() {
            if (errors.isEmpty() && warnings.isEmpty())
                return "ok: the schemas map coherently";
            List<String> lines = new ArrayList<>();
            for (String e : errors)
                lines.add("error: " + e);
            for (String w : warnings)
                lines.add("warning: " + w);
            return String.join("\n", lines);
        }
    }

    /** Check that {@code output} can be materialized coherently from what {@code input} binds.
     *
     * @param inputStart the input shape label; the schema's start when null
     * @param outputStart the output shape label; the schema's start when null
     * @param staticVars variable IRIs that will be supplied as static variables */
    public static Report analyse(ShexSchema input, ShexSchema output, Node inputStart, Node outputStart, Set<String> staticVars) {
        SchemaIndex in = new SchemaIndex(input);
        SchemaIndex out = new SchemaIndex(output);
        Report report = new Report();
        report.prefixes.putAll(out.prefixes());
        for (Map.Entry<String, String> e : in.prefixes().entrySet())
            report.prefixes.putIfAbsent(e.getKey(), e.getValue());
        new Input(in, report).shapeExpr(in.startExpr(inputStart), List.of());
        Set<String> statics = staticVars == null ? Set.of() : staticVars;
        new Output(out, report, statics).shapeExpr(out.startExpr(outputStart), List.of());
        for (Map.Entry<String, List<Integer>> e : new LinkedHashMap<>(report.bound).entrySet())
            if (!report.read.contains(e.getKey()))
                report.warn(e.getKey() + " is bound (at " + report.where(e.getValue()) + ") but the output never reads it");
        for (String v : new TreeSet<>(statics))
            if (!report.read.contains(v))
                report.warn("static variable " + v + " is never read");
        return report;
    }

    /** (variables bound or read by a constraint's Map codes, id() arguments) -- id() is not a
     * variable code; its arguments are returned apart. */
    private static List<List<String>> variables(Tc tc, Map<String, String> prefixes) {
        List<String> plain = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (SemAct act : tc.mapActions()) {
            String code = SchemaIndex.codeOf(act);
            try {
                if (MapFunctions.isKeyCode(code)) {
                    for (String a : MapFunctions.keyArguments(code, prefixes))
                        if (!a.equals(MapFunctions.NODE_ARGUMENT))
                            keys.add(a);
                } else if (MapFunctions.isFunctionCall(code)) {
                    plain.addAll(MapFunctions.variablesOf(code, prefixes));
                } else {
                    plain.add(MapFunctions.expandVariable(code, prefixes));
                }
            } catch (MapFunctionException e) {
                // a malformed code fails where it is evaluated
            }
        }
        return List.of(plain, keys);
    }

    private static boolean hasNodeArgument(Tc tc, Map<String, String> prefixes) {
        for (SemAct a : tc.mapActions()) {
            String code = SchemaIndex.codeOf(a);
            if (MapFunctions.isKeyCode(code)) {
                try {
                    if (MapFunctions.keyArguments(code, prefixes).contains(MapFunctions.NODE_ARGUMENT))
                        return true;
                } catch (MapFunctionException e) {
                    // reported elsewhere
                }
            }
        }
        return false;
    }

    private static List<Integer> plus(List<Integer> site, int k) {
        List<Integer> out = new ArrayList<>(site);
        out.add(k);
        return out;
    }

    /** Where the input schema binds each variable. */
    private static final class Input {
        final SchemaIndex index;
        final Report report;
        final Set<Integer> seen = new HashSet<>();

        Input(SchemaIndex index, Report report) { this.index = index; this.report = report; }

        void shapeExpr(ShapeExpr se, List<Integer> site) {
            if (se instanceof ShapeExprRef ref) {
                ShapeDecl decl = index.declaration(ref.getLabel());
                if (decl != null)
                    for (ShapeDecl o : index.extensionCandidates(decl))
                        shapeExpr(o.getShapeExpr(), site);
                return;
            }
            if (!seen.add(se.id))
                return;
            if (se instanceof Shape shape) {
                for (Shape p : index.shapeParts(shape))
                    if (p.getTripleExpr() != null)
                        expression(p.getTripleExpr(), site);
            } else if (se instanceof ShapeAnd and) {
                for (ShapeExpr p : and.getShapeExprs())
                    shapeExpr(p, site);
            } else if (se instanceof ShapeOr or) {
                for (ShapeExpr p : or.getShapeExprs())
                    shapeExpr(p, site);
            }
        }

        void expression(TripleExpr expr, List<Integer> site) {
            expr = index.resolve(expr);
            if (SchemaIndex.isRepeated(expr)) {
                report.labels.put(SchemaIndex.key(expr), index.labelOf(expr));
                site = plus(site, SchemaIndex.key(expr));
            }
            Tc tc = Tc.of(expr);
            if (tc != null) {
                for (String v : variables(tc, report.prefixes).get(0)) {
                    List<Integer> at = report.bound.putIfAbsent(v, site);
                    if (at != null && !at.equals(site))
                        report.error(v + " is bound at two places of the input schema, " + report.where(at) + " and "
                                + report.where(site) + "; a variable must have one binding site");
                }
                if (tc.referencesShape())
                    shapeExpr(tc.valueExpr(), site);
            } else {
                for (TripleExpr e : SchemaIndex.subExpressions(expr))
                    expression(e, site);
            }
        }
    }

    /** Where the output schema reads each variable, and what each repetition iterates. */
    private static final class Output {
        final SchemaIndex index;
        final Report report;
        final Set<String> statics;
        final Map<Integer, List<Integer>> scopes = new HashMap<>();
        final Set<Integer> scopesComputed = new HashSet<>();
        final Set<Integer> illFormed = new HashSet<>();
        final Set<Integer> active = new HashSet<>();

        Output(SchemaIndex index, Report report, Set<String> statics) {
            this.index = index; this.report = report; this.statics = statics;
        }

        void shapeExpr(ShapeExpr se, List<Integer> scope) {
            if (se instanceof ShapeExprRef ref) {
                ShapeDecl decl = index.declaration(ref.getLabel());
                if (decl == null) {
                    report.error("the output schema references " + SchemaIndex.labelText(ref.getLabel()) + ", which it does not define");
                    return;
                }
                List<ShapeDecl> options = index.extensionCandidates(decl);
                if (options.isEmpty())
                    report.error(SchemaIndex.labelText(ref.getLabel()) + " is abstract and nothing extends it");
                for (ShapeDecl o : options)
                    shapeExpr(o.getShapeExpr(), scope);
                return;
            }
            if (!active.add(se.id))
                return;                       // a recursive shape: the first pass covered it
            try {
                if (se instanceof Shape shape) {
                    for (Shape p : index.shapeParts(shape))
                        if (p.getTripleExpr() != null)
                            expression(p.getTripleExpr(), scope);
                } else if (se instanceof ShapeAnd and) {
                    for (ShapeExpr p : and.getShapeExprs())
                        if (!(p instanceof NodeConstraint))
                            shapeExpr(p, scope);
                } else if (se instanceof ShapeOr or) {
                    for (ShapeExpr p : or.getShapeExprs())
                        if (!(p instanceof NodeConstraint))
                            shapeExpr(p, scope);
                } else if (!(se instanceof NodeConstraint)) {
                    report.error(SchemaIndex.kindOf(se) + " cannot be materialized");
                }
            } finally {
                active.remove(se.id);
            }
        }

        void expression(TripleExpr expr, List<Integer> scope) {
            expr = index.resolve(expr);
            List<Integer> bodyScope = scope;
            if (SchemaIndex.isRepetition(expr)) {
                List<Integer> iterates = iterationScope(expr);
                if (illFormed.contains(SchemaIndex.key(expr)))
                    return;                   // its structure is the error; its reads would only repeat it
                if (iterates != null && iterates.size() > scope.size())
                    bodyScope = iterates;
            }
            Tc tc = Tc.of(expr);
            if (tc != null)
                constraint(tc, bodyScope);
            else
                for (TripleExpr e : SchemaIndex.subExpressions(expr))
                    expression(e, bodyScope);
        }

        void constraint(Tc tc, List<Integer> scope) {
            List<List<String>> vars = variables(tc, report.prefixes);
            List<SemAct> acts = tc.mapActions();
            String pred = tc.predicateStr();
            boolean keyed = !vars.get(1).isEmpty() || acts.stream().anyMatch(a -> MapFunctions.isKeyCode(SchemaIndex.codeOf(a)));
            if (keyed) {
                if (acts.size() > 1)
                    report.error("id() must be the only Map code on " + pred);
                if (!tc.referencesShape())
                    report.error("id() on " + pred + " needs a shape-valued constraint");
                if (hasNodeArgument(tc, report.prefixes) && scope.isEmpty())
                    report.error("id(@node) on " + pred + " is read at the root, where no input iteration provides a node");
            }
            for (String v : vars.get(0))
                readVariable(v, pred, scope);
            for (String v : vars.get(1))
                readVariable(v, pred, scope);
            if (tc.referencesShape())
                shapeExpr(tc.valueExpr(), scope);
        }

        void readVariable(String v, String pred, List<Integer> scope) {
            report.read.add(v);
            if (statics.contains(v))
                return;
            List<Integer> site = report.bound.get(v);
            if (site == null) {
                report.error(pred + " reads " + v + ", which the input schema never binds");
                return;
            }
            if (ScopeTree.isPrefix(site, scope))
                return;                       // bound here or above: readable
            if (ScopeTree.isPrefix(scope, site))
                report.error(pred + " reads " + v + ", bound once per " + report.where(site) + ", from " + report.where(scope)
                        + ": which one?  A repetition over it is needed");
            else
                report.error(pred + " reads " + v + ", bound at " + report.where(site) + ", from " + report.where(scope)
                        + ", which is not below it");
        }

        List<Integer> iterationScope(TripleExpr expr) {
            int k = SchemaIndex.key(expr);
            if (scopesComputed.contains(k))
                return scopes.get(k);
            scopesComputed.add(k);
            scopes.put(k, null);              // a recursive repetition counts as scope-free while computed
            Set<String> direct = new LinkedHashSet<>();
            List<TripleExpr> nested = new ArrayList<>();
            collect(expr, direct, nested, new HashSet<>(), true);
            Map<List<Integer>, String> candidates = new LinkedHashMap<>();
            for (String v : direct) {
                List<Integer> site = report.bound.get(v);
                if (site != null)
                    candidates.putIfAbsent(site, v);
            }
            for (TripleExpr r : nested) {
                List<Integer> sub = iterationScope(r);
                if (sub != null && !sub.isEmpty())
                    candidates.putIfAbsent(sub.subList(0, sub.size() - 1), "the repetition over " + index.labelOf(r));
            }
            List<Integer> result = null;
            if (!candidates.isEmpty()) {
                for (List<Integer> s : candidates.keySet())
                    if (result == null || s.size() > result.size())
                        result = s;
                for (Map.Entry<List<Integer>, String> e : candidates.entrySet())
                    if (!ScopeTree.isPrefix(e.getKey(), result)) {
                        report.error("the repetition over " + index.labelOf(expr) + " reads from unrelated lists: " + e.getValue()
                                + " is bound at " + report.where(e.getKey()) + " and " + candidates.get(result) + " at "
                                + report.where(result));
                        illFormed.add(k);
                        report.read.addAll(direct);
                        break;
                    }
            }
            scopes.put(k, result);
            return result;
        }

        void collect(TripleExpr expr, Set<String> direct, List<TripleExpr> nested, Set<Integer> seen, boolean top) {
            expr = index.resolve(expr);
            if (!seen.add(SchemaIndex.key(expr)))
                return;
            if (!top && SchemaIndex.isRepetition(expr)) {
                nested.add(expr);
                return;
            }
            Tc tc = Tc.of(expr);
            if (tc != null) {
                List<List<String>> vars = variables(tc, report.prefixes);
                direct.addAll(vars.get(0));
                direct.addAll(vars.get(1));
                if (tc.referencesShape())
                    collectShape(tc.valueExpr(), direct, nested, seen);
            } else {
                for (TripleExpr e : SchemaIndex.subExpressions(expr))
                    collect(e, direct, nested, seen, false);
            }
        }

        void collectShape(ShapeExpr se, Set<String> direct, List<TripleExpr> nested, Set<Integer> seen) {
            if (se instanceof ShapeExprRef ref) {
                ShapeDecl decl = index.declaration(ref.getLabel());
                if (decl != null)
                    for (ShapeDecl o : index.extensionCandidates(decl))
                        collectShape(o.getShapeExpr(), direct, nested, seen);
                return;
            }
            if (!seen.add(se.id))
                return;
            if (se instanceof Shape shape) {
                for (Shape p : index.shapeParts(shape))
                    if (p.getTripleExpr() != null)
                        collect(p.getTripleExpr(), direct, nested, seen, false);
            } else if (se instanceof ShapeAnd and) {
                for (ShapeExpr p : and.getShapeExprs())
                    collectShape(p, direct, nested, seen);
            } else if (se instanceof ShapeOr or) {
                for (ShapeExpr p : or.getShapeExprs())
                    collectShape(p, direct, nested, seen);
            }
        }
    }
}
