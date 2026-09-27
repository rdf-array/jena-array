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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.shex.ShapeDecl;
import org.apache.jena.shex.ShexSchema;
import org.apache.jena.shex.expressions.Cardinality;
import org.apache.jena.shex.expressions.DatatypeConstraint;
import org.apache.jena.shex.expressions.EachOf;
import org.apache.jena.shex.expressions.NodeConstraint;
import org.apache.jena.shex.expressions.NodeConstraintComponent;
import org.apache.jena.shex.expressions.OneOf;
import org.apache.jena.shex.expressions.SemAct;
import org.apache.jena.shex.expressions.Shape;
import org.apache.jena.shex.expressions.ShapeAnd;
import org.apache.jena.shex.expressions.ShapeExpr;
import org.apache.jena.shex.expressions.ShapeExprRef;
import org.apache.jena.shex.expressions.ShapeNot;
import org.apache.jena.shex.expressions.ShapeOr;
import org.apache.jena.shex.expressions.TripleExpr;
import org.apache.jena.shex.expressions.TripleExprEmpty;
import org.apache.jena.shex.reporting.ValidateOnlyReporter;
import org.apache.jena.shex.validation.ShapeExprEval;
import org.apache.jena.shex.validation.ValidationContext;
import org.apache.jena.shexmap.SchemaIndex.Tc;
import org.apache.jena.shexmap.ScopeTree.Scope;
import org.apache.jena.sparql.graph.GraphFactory;

/** Materialization by iteration scopes: build RDF conforming to an output schema from bindings.
 * <p>
 * The binding tree is read as a scope tree ({@link ScopeTree}): a scope's own bindings, and one
 * list of iteration scopes per repeated constraint or group of the input.  Nothing is flattened
 * and nothing is consumed:
 * <ul>
 * <li>A constraint with a Map variable <em>reads</em> it from the scope it is evaluated at, or from
 *     the nearest ancestor scope that binds it.  Reading marks nothing, so a parent's binding can
 *     be read in every item, and twice in one.  A variable bound nowhere on that chain fails the
 *     constraint.</li>
 * <li>A <em>repetition</em> in the output schema iterates one list of the input: the deepest list
 *     its body's variables are bound at (nested repetitions count through their parent list).  Its
 *     body runs once per iteration of that list, in list order, at that iteration's scope; an
 *     iteration whose body fails is skipped; the count of successful iterations must reach the
 *     minimum, and the repetition stops at the maximum.  A body whose variables are all bound at or
 *     above the current scope runs once, there.  A body whose variables live in two lists neither
 *     of which contains the other is ill-formed, and says so.</li>
 * <li><em>Choices</em> ({@code OneOf}, {@code ShapeOr}, an optional constraint, a shape with
 *     extensions) yield alternatives, per scope; the alternatives of the parts of a group or
 *     conjunction combine by product, pruned to the best {@code maxAccepts} as they combine.</li>
 * <li>Every complete alternative is an {@link Accept}; {@link #materialize} returns the one that
 *     read the most distinct bindings (ties: most triples, then schema order), or whichever
 *     {@code prefer} ranks first, and keeps the rest in {@link #accepts()}.</li>
 * <li>A value a Map code produces must <em>satisfy the constraint's value expression</em> when that
 *     is a node constraint.  A plain literal whose lexical form is valid for the constraint's
 *     datatype is retyped to it, so what {@code regex()} and {@code hashmap()} produce can land in
 *     a typed constraint; any other mismatch fails the constraint like an unbound variable
 *     ({@code checkValues(false)} turns this off).</li>
 * <li>A shape-valued constraint that also carries a Map variable <em>names</em> its node: the bound
 *     value is the node and the nested shape is materialized on it.  One that carries
 *     {@code %Map:{ id(v:a, v:b) %}} is <em>keyed</em>: its node is a function of the shape and the
 *     values read for the key, so two constraints, or two iterations, with the same key make the
 *     same node and their arcs merge.  A key of one IRI or blank node is that node itself;
 *     {@code id(@node)} reuses the input node the enclosing iteration matched.  Otherwise the node
 *     is a blank node determined by the root, the constraint, and the scope, so two runs over the
 *     same bindings agree.</li>
 * </ul>
 * {@code EXTENDS} in the output schema materializes the extended shapes' constraints on the same
 * node, and a reference to a shape that has extensions may materialize any non-abstract one of
 * them. */
public final class Materializer {

    /** A {@code (scope path, variable)} address of a binding read. */
    public record Read(List<Integer> path, String variable) {}

    /** How the object of an output triple arose. */
    public enum SourceKind { VARIABLE, CODE, CONSTANT, STRUCTURAL, NAMED, KEYED }

    /** The provenance of one output triple: the output constraint, the input scope it was
     * evaluated at (its path and the node it matched), the bindings and static variables read for
     * it, and how the object arose. */
    public record Source(String predicate, List<Integer> scope, Node node, List<Read> reads,
                         List<String> statics, SourceKind kind, String detail) {}

    /** A complete materialization. */
    public record Accept(List<Triple> triples, int consumed, int skipped, Set<Read> used, List<Source> provenance) {}

    /** One dead end met while materializing. */
    public record Failure(String predicate, String variable, String code, String error, List<Integer> scope) {
        @Override public String toString() {
            String what = variable != null ? variable : code != null ? code : "";
            return (predicate + " " + what + " " + (error != null ? error : "unbound")).strip();
        }
    }

    /** What the last run found, beyond its triples. */
    public record Report(List<Failure> unboundVariables, List<String> unusedStatics, int alternatives,
                         boolean explorationTruncated, int configsPruned) {}

    /** The triples an in-place update added and removed. */
    public record Update(List<Triple> added, List<Triple> removed) {}

    /** Options of a materializer. */
    public static final class Options {
        Map<String, Node> staticVars = new LinkedHashMap<>();
        Map<String, String> prefixes = new LinkedHashMap<>();
        Comparator<Accept> prefer = null;
        Integer maxRepeat = null;
        int maxCallDepth = 50;
        int maxAccepts = 20;
        boolean requireBindingsInSubshapes = false;
        boolean checkValues = true;

        /** Variable values available everywhere (shex.js's {@code staticVars}). */
        public Options staticVar(String variable, Node value) { staticVars.put(variable, value); return this; }
        public Options staticVars(Map<String, Node> vars) { staticVars.putAll(vars); return this; }
        /** Extra prefixes for ShExMap variable names, beside the schema's. */
        public Options prefix(String alias, String namespace) { prefixes.put(alias, namespace); return this; }
        /** Comparator over accepts: negative when the first is better. */
        public Options prefer(Comparator<Accept> c) { prefer = c; return this; }
        /** Cap on the iterations of one repetition; null for none. */
        public Options maxRepeat(Integer n) { maxRepeat = n; return this; }
        /** Cap on nested shape calls (recursive schemas). */
        public Options maxCallDepth(int n) { maxCallDepth = n; return this; }
        /** How many alternatives to keep, at every level and at the end. */
        public Options maxAccepts(int n) { maxAccepts = n; return this; }
        /** Drop optional subshapes that read no binding. */
        public Options requireBindingsInSubshapes(boolean b) { requireBindingsInSubshapes = b; return this; }
        /** Check bound values against node constraints, retyping plain literals whose lexical form
         * fits the datatype. */
        public Options checkValues(boolean b) { checkValues = b; return this; }
    }

    /** One way to materialize something: its triples, where each came from, and the bindings
     * it read. */
    private static final class Alt {
        final List<Triple> quads;
        final Set<Read> reads;
        final List<Source> sources;

        Alt() { this(List.of(), Set.of(), List.of()); }

        Alt(List<Triple> quads, Set<Read> reads, List<Source> sources) {
            this.quads = List.copyOf(quads);
            this.reads = Set.copyOf(reads);
            this.sources = List.copyOf(sources);
        }

        Alt then(Alt other) {
            List<Triple> q = new ArrayList<>(quads);
            q.addAll(other.quads);
            Set<Read> r = new LinkedHashSet<>(reads);
            r.addAll(other.reads);
            List<Source> s = new ArrayList<>(sources);
            s.addAll(other.sources);
            return new Alt(q, r, s);
        }

        int rankReads() { return reads.size(); }
        int rankQuads() { return quads.size(); }
    }

    private static final Alt EMPTY = new Alt();

    private final SchemaIndex index;
    private final ShexSchema schema;
    private final Options options;
    private final ValidationContext checkCxt;
    private List<Accept> accepts = new ArrayList<>();
    private Integer chosen = null;
    private Report lastReport = new Report(List.of(), List.of(), 0, false, 0);
    private Node root;

    // per run
    private ScopeTree tree;
    private final Map<Integer, List<Integer>> lists = new HashMap<>();      // expression id -> list path (null: none)
    private final Set<Integer> listsComputed = new HashSet<>();
    private final Map<Integer, Integer> tcIndex = new HashMap<>();
    private final List<String[]> active = new ArrayList<>();                 // (guard key, label)
    private final List<Failure> failures = new ArrayList<>();
    private final Set<String> referenced = new HashSet<>();
    private int dropped = 0;

    public Materializer(ShexSchema schema) {
        this(schema, new Options());
    }

    public Materializer(ShexSchema schema, Options options) {
        this.schema = schema;
        this.index = new SchemaIndex(schema);
        this.options = options;
        this.index.prefixes().putAll(options.prefixes);
        this.checkCxt = new ValidationContext(schema, GraphFactory.createDefaultGraph(), Map.of(), true, true);
    }

    /** The prefixes ShExMap variable names use (the schema's, plus the options'). */
    public Map<String, String> prefixes() { return index.prefixes(); }

    /** The alternatives of the last run, best first. */
    public List<Accept> accepts() { return accepts; }

    /** The materialization the last run returned. */
    public Accept chosen() { return chosen == null ? null : accepts.get(chosen); }

    /** The provenance of the chosen materialization, parallel to its triples. */
    public List<Source> provenance() { return chosen == null ? null : accepts.get(chosen).provenance(); }

    public Report lastReport() { return lastReport; }

    /** The root the last run built. */
    public Node root() { return root; }

    // -- running --------------------------------------------------------------------------------

    /** The triples of the best materialization of {@code start} (default: the schema's start)
     * rooted at {@code root} (default: a new blank node).  The alternatives are kept in
     * {@link #accepts()}, best first; the returned one is {@link #chosen()}.
     * @throws MaterializationException when nothing materializes, or the bindings are not a scope
     *         tree, or a repetition reads variables from unrelated lists */
    public List<Triple> materialize(BindingTree bindings, Node root, Node start) {
        try {
            tree = new ScopeTree(bindings);
        } catch (BindingTreeException e) {
            throw new MaterializationException(e.getMessage());
        }
        this.root = root != null ? root : NodeFactory.createBlankNode("root" + Terms.digest(bindings.canonical()));
        ShapeExpr se = index.startExpr(start);
        lists.clear();
        listsComputed.clear();
        tcIndex.clear();
        active.clear();
        failures.clear();
        referenced.clear();
        dropped = 0;
        String label = start == null ? "START" : SchemaIndex.labelText(start);
        List<Alt> results = shapeExpr(se, tree.root(), this.root, 0, label);
        results.sort(Comparator.comparingInt(Alt::rankReads).thenComparingInt(Alt::rankQuads).reversed()); // stable
        Set<Set<Triple>> seen = new HashSet<>();
        List<Accept> found = new ArrayList<>();
        for (Alt r : results) {
            List<Triple> triples = new ArrayList<>();
            List<Source> provenance = new ArrayList<>();
            Set<Triple> distinct = new LinkedHashSet<>();
            for (int i = 0; i < r.quads.size(); i++)
                if (distinct.add(r.quads.get(i))) {
                    triples.add(r.quads.get(i));
                    provenance.add(r.sources.get(i));
                }
            if (!seen.add(distinct))
                continue;
            found.add(new Accept(triples, r.reads.size(), 0, r.reads, provenance));
        }
        accepts = found;
        lastReport = report(found.size());
        if (accepts.isEmpty())
            throw new MaterializationException("nothing materializes the shape from these bindings", failures);
        int best = 0;
        if (options.prefer != null)
            for (int i = 1; i < accepts.size(); i++)
                if (options.prefer.compare(accepts.get(i), accepts.get(best)) < 0)
                    best = i;
        chosen = best;
        return accepts.get(best).triples();
    }

    public List<Triple> materialize(Bindings bindings, Node root, Node start) {
        return materialize(bindings.tree(), root, start);
    }

    /** Materialize into {@code graph}, replacing what the output schema currently holds at
     * {@code root}: the schema, used as an input schema on {@code graph} at {@code root}, says
     * which triples it governs there; those no longer produced are removed, the new ones added,
     * and everything else is left alone.
     * <p>
     * Whether the graph holds an earlier materialization is decided by the predicates the output
     * schema's start shape constrains: a triple at {@code root} on one of them means there is
     * something to replace, and it must then conform.
     * @throws MaterializationException when {@code graph} holds something at {@code root} that
     *         does not conform to the output schema (so what to replace cannot be told), or
     *         nothing materializes */
    public Update update(Graph graph, BindingTree bindings, Node root, Node start) {
        Set<Triple> fresh = new LinkedHashSet<>(materialize(bindings, root, start));
        Node rootNode = this.root;
        Set<Triple> current = new LinkedHashSet<>();
        if (holdsSomething(graph, rootNode, start)) {
            try {
                for (Bindings b : Binder.bindAll(graph, schema, rootNode, start, options.maxAccepts, null))
                    current.addAll(b.matched());
            } catch (MapValidationException e) {
                throw new MaterializationException(Terms.n3(rootNode) + " holds something that does not conform to "
                        + "the output schema, so what to replace cannot be told: " + e.getMessage());
            }
        }
        List<Triple> removed = new ArrayList<>();
        for (Triple t : current)
            if (!fresh.contains(t))
                removed.add(t);
        List<Triple> added = new ArrayList<>();
        for (Triple t : fresh)
            if (!graph.contains(t))
                added.add(t);
        for (Triple t : removed)
            graph.delete(t);
        for (Triple t : fresh)
            graph.add(t);
        return new Update(added, removed);
    }

    private boolean holdsSomething(Graph graph, Node rootNode, Node start) {
        ShapeExpr se = index.startExpr(start);
        for (Object[] pi : ownPredicates(se, new HashSet<>())) {
            Node pred = (Node) pi[0];
            boolean inverse = (Boolean) pi[1];
            boolean found = inverse
                    ? graph.contains(Node.ANY, pred, rootNode)
                    : Terms.isResource(rootNode) && graph.contains(rootNode, pred, Node.ANY);
            if (found)
                return true;
        }
        return false;
    }

    /** {@code (predicate, inverse)} of the constraints on the shape's own node, extensions included. */
    private List<Object[]> ownPredicates(ShapeExpr se, Set<Integer> seen) {
        List<Object[]> out = new ArrayList<>();
        if (se instanceof ShapeExprRef ref) {
            ShapeDecl decl = index.declaration(ref.getLabel());
            if (decl != null)
                for (ShapeDecl o : index.extensionCandidates(decl))
                    out.addAll(ownPredicates(o.getShapeExpr(), seen));
            return out;
        }
        if (!seen.add(se.id))
            return out;
        if (se instanceof Shape shape) {
            List<TripleExpr> stack = new ArrayList<>();
            for (Shape p : index.shapeParts(shape))
                if (p.getTripleExpr() != null)
                    stack.add(p.getTripleExpr());
            while (!stack.isEmpty()) {
                TripleExpr e = index.resolve(stack.remove(stack.size() - 1));
                Tc tc = Tc.of(e);
                if (tc != null)
                    out.add(new Object[] { tc.predicate(), tc.inverse() });
                else
                    stack.addAll(SchemaIndex.subExpressions(e));
            }
        } else if (se instanceof ShapeAnd and) {
            for (ShapeExpr p : and.getShapeExprs())
                out.addAll(ownPredicates(p, seen));
        } else if (se instanceof ShapeOr or) {
            for (ShapeExpr p : or.getShapeExprs())
                out.addAll(ownPredicates(p, seen));
        }
        return out;
    }

    private Report report(int found) {
        Set<String> available = new HashSet<>(options.staticVars.keySet());
        if (tree != null)
            available.addAll(tree.variables());
        Set<String> seen = new HashSet<>();
        List<Failure> unbound = new ArrayList<>();
        for (Failure f : failures)
            if (f.variable() != null && !available.contains(f.variable()) && seen.add(f.variable() + "|" + f.predicate()))
                unbound.add(f);
        List<String> unused = new ArrayList<>();
        for (String s : options.staticVars.keySet())
            if (!referenced.contains(s))
                unused.add(s);
        java.util.Collections.sort(unused);
        return new Report(unbound, unused, found, dropped > 0, dropped);
    }

    // -- shape expressions ----------------------------------------------------------------------

    private List<Alt> shapeExpr(ShapeExpr se, int scope, Node subject, int depth, String label) {
        List<Integer> path = tree.scope(scope).path;
        if (se instanceof ShapeExprRef ref) {
            ShapeDecl decl = index.declaration(ref.getLabel());
            if (decl == null)
                throw new MaterializationException("Shape " + SchemaIndex.labelText(ref.getLabel()) + " is not defined in the output schema");
            List<ShapeDecl> options_ = new ArrayList<>(index.extensionCandidates(decl));
            if (options_.isEmpty())
                throw new MaterializationException("Shape " + SchemaIndex.labelText(ref.getLabel()) + " is abstract and nothing extends it");
            java.util.Collections.reverse(options_);      // the declaration itself first, then its extensions
            String text = SchemaIndex.labelText(ref.getLabel());
            return guarded("ref|" + decl.getLabel() + "|" + path + "|" + Terms.n3(subject), text, () -> {
                List<Alt> out = new ArrayList<>();
                for (ShapeDecl o : options_)
                    out.addAll(shapeExpr(o.getShapeExpr(), scope, subject, depth, text));
                return prune(out);
            });
        }
        String lbl = label != null ? label : "(inline " + SchemaIndex.kindOf(se) + ")";
        if (se instanceof Shape shape) {
            return guarded("shape|" + se.id + "|" + path + "|" + Terms.n3(subject), lbl, () -> {
                List<List<Alt>> alts = new ArrayList<>();
                for (Shape p : index.shapeParts(shape)) {
                    TripleExpr e = p.getTripleExpr();
                    alts.add(e == null || e instanceof TripleExprEmpty ? List.of(EMPTY) : expression(e, scope, subject, depth));
                }
                return all(alts);
            });
        }
        if (se instanceof ShapeAnd and) {
            return guarded("and|" + se.id + "|" + path + "|" + Terms.n3(subject), lbl, () -> {
                List<List<Alt>> alts = new ArrayList<>();
                for (ShapeExpr p : and.getShapeExprs())
                    if (!(p instanceof NodeConstraint))
                        alts.add(shapeExpr(p, scope, subject, depth, null));
                return all(alts);
            });
        }
        if (se instanceof ShapeOr or) {
            return guarded("or|" + se.id + "|" + path + "|" + Terms.n3(subject), lbl, () -> {
                List<Alt> out = new ArrayList<>();
                for (ShapeExpr p : or.getShapeExprs())
                    if (!(p instanceof NodeConstraint))
                        out.addAll(shapeExpr(p, scope, subject, depth, null));
                return prune(out);
            });
        }
        if (se instanceof NodeConstraint)
            return List.of(EMPTY);
        throw new MaterializationException(SchemaIndex.kindOf(se) + " synthesis is not supported");
    }

    /** Evaluate, refusing a shape expression that reaches itself at the same scope and subject
     * through references alone ({@code <A> @<B> OR ...}, {@code <B> @<A> OR ...}). */
    private List<Alt> guarded(String key, String label, java.util.function.Supplier<List<Alt>> run) {
        for (int i = 0; i < active.size(); i++)
            if (active.get(i)[0].equals(key)) {
                List<String> chain = new ArrayList<>();
                for (int j = i; j < active.size(); j++)
                    chain.add(active.get(j)[1]);
                throw new MaterializationException("cycle in shape expressions: " + String.join(" -> ", chain) + " -> " + label);
            }
        active.add(new String[] { key, label });
        try {
            return run.get();
        } finally {
            active.remove(active.size() - 1);
        }
    }

    // -- triple expressions ---------------------------------------------------------------------

    private List<Alt> expression(TripleExpr expr, int scope, Node subject, int depth) {
        expr = index.resolve(expr);
        Cardinality c = SchemaIndex.cardinality(expr);
        if (c.min == 1 && c.max == 1)
            return once(expr, scope, subject, depth, false);
        return repetition(expr, c.min, c.max, scope, subject, depth);
    }

    /** The alternatives for one occurrence of {@code expr}, cardinality aside. */
    private List<Alt> once(TripleExpr expr, int scope, Node subject, int depth, boolean skippable) {
        Tc tc = Tc.of(expr);
        if (tc != null)
            return tc(tc, scope, subject, depth, skippable);
        TripleExpr in = SchemaIndex.inner(expr);
        if (in instanceof EachOf) {
            List<List<Alt>> alts = new ArrayList<>();
            for (TripleExpr e : SchemaIndex.subExpressions(expr))
                alts.add(expression(e, scope, subject, depth));
            return all(alts);
        }
        if (in instanceof OneOf) {
            List<Alt> out = new ArrayList<>();
            for (TripleExpr e : SchemaIndex.subExpressions(expr))
                out.addAll(expression(e, scope, subject, depth));
            return prune(out);
        }
        if (in instanceof TripleExprEmpty)
            return List.of(EMPTY);
        throw new MaterializationException("unexpected triple expression " + in.getClass().getSimpleName());
    }

    private List<Alt> repetition(TripleExpr expr, int min, int max, int scope, Node subject, int depth) {
        List<Integer> listPath = listPath(expr);
        boolean skippable = min == 0;
        int depthHere = tree.scope(scope).depth();
        if (listPath == null || listPath.size() <= depthHere) {
            // the body reads nothing below this scope: it runs once, here
            if (min > 1)
                return List.of();
            List<Alt> body = once(expr, scope, subject, depth, skippable);
            if (!body.isEmpty())
                return body;
            return min == 0 ? List.of(EMPTY) : List.of();
        }
        long cap = max;
        if (options.maxRepeat != null)
            cap = Math.min(cap, options.maxRepeat);
        List<Alt> results = List.of(EMPTY);
        int count = 0;
        for (int item : tree.descendantsAt(scope, listPath)) {
            if (count >= cap)
                break;
            List<Alt> body = once(expr, item, subject, depth, skippable);
            if (body.isEmpty())
                continue;                     // this item does not fit the body: skip it
            results = all(List.of(results, body));
            count++;
        }
        return count >= min ? results : List.of();
    }

    private void fail(String predicate, String variable, String code, String error, List<Integer> scope) {
        failures.add(new Failure(predicate, variable, code, error, scope));
    }

    private Triple tripleFor(Tc tc, Node subject, Node obj) {
        if (tc.inverse()) {
            if (!Terms.isResource(obj)) {
                fail(tc.predicateStr(), null, null, "literal subject of inverse", null);
                return null;
            }
            return Triple.create(obj, tc.predicate(), subject);
        }
        return Triple.create(subject, tc.predicate(), obj);
    }

    private Source source(Tc tc, int scope, List<Read> reads, List<String> statics, SourceKind kind, String detail) {
        Scope s = tree.scope(scope);
        return new Source(tc.predicateStr(), s.path, tree.nearestNode(scope), List.copyOf(reads), List.copyOf(statics), kind, detail);
    }

    /** Read a variable at a scope: statics first, then the scope chain.  Records the read. */
    private Node get(int scope, String v, Set<Read> reads, List<String> statics) {
        referenced.add(v);
        Node s = options.staticVars.get(v);
        if (s != null) {
            statics.add(v);
            return s;
        }
        Map.Entry<Node, Integer> hit = tree.lookup(scope, v);
        if (hit == null)
            return null;
        reads.add(new Read(tree.scope(hit.getValue()).path, v));
        return hit.getKey();
    }

    private List<Alt> tc(Tc tc, int scope, Node subject, int depth, boolean skippable) {
        String pred = tc.predicateStr();
        List<String> acts = new ArrayList<>();
        for (SemAct a : tc.mapActions())
            acts.add(SchemaIndex.codeOf(a));
        if (acts.stream().anyMatch(MapFunctions::isKeyCode))
            return keyed(tc, acts, scope, subject, depth, skippable);
        List<Integer> scopePath = tree.scope(scope).path;
        if (!acts.isEmpty()) {
            Set<Read> reads = new LinkedHashSet<>();
            List<Triple> quads = new ArrayList<>();
            List<Node> objects = new ArrayList<>();
            List<Source> sources = new ArrayList<>();
            for (String code : acts) {
                Set<Read> before = new HashSet<>(reads);
                List<String> staticsRead = new ArrayList<>();
                Node value;
                SourceKind kind;
                String detail;
                try {
                    if (MapFunctions.isFunctionCall(code)) {
                        value = MapFunctions.lower(code, v -> get(scope, v, reads, staticsRead), index.prefixes());
                        if (value == null) {
                            fail(pred, null, code, "unbound", null);
                            return List.of();
                        }
                        kind = SourceKind.CODE;
                        detail = code.strip();
                    } else {
                        String var = MapFunctions.expandVariable(code, index.prefixes());
                        value = get(scope, var, reads, staticsRead);
                        if (value == null) {
                            fail(pred, var, null, null, scopePath);
                            return List.of();
                        }
                        kind = SourceKind.VARIABLE;
                        detail = var;
                    }
                } catch (MapFunctionException e) {
                    fail(pred, null, code, e.getMessage(), null);
                    return List.of();
                }
                if (options.checkValues) {
                    value = checked(tc, value);
                    if (value == null) {
                        fail(pred, null, code, "the value does not satisfy the value expression", null);
                        return List.of();
                    }
                }
                Triple t = tripleFor(tc, subject, value);
                if (t == null)
                    return List.of();
                quads.add(t);
                objects.add(value);
                List<Read> newReads = new ArrayList<>();
                for (Read r : reads)
                    if (!before.contains(r))
                        newReads.add(r);
                sources.add(source(tc, scope, newReads, staticsRead, kind, detail));
            }
            if (tc.referencesShape() && objects.size() == 1 && Terms.isResource(objects.get(0))) {
                // the variable names the node: materialize the nested shape on it
                if (depth >= options.maxCallDepth) {
                    fail(pred, null, null, "exceeded maxCallDepth", null);
                    return List.of();
                }
                Source s0 = sources.get(0);
                if (s0.kind() == SourceKind.VARIABLE)
                    sources.set(0, new Source(s0.predicate(), s0.scope(), s0.node(), s0.reads(), s0.statics(), SourceKind.NAMED, s0.detail()));
                Alt head = new Alt(quads, reads, sources);
                List<Alt> out = new ArrayList<>();
                for (Alt n : shapeExpr(tc.valueExpr(), scope, objects.get(0), depth + 1, null))
                    out.add(head.then(n));
                return out;
            }
            return List.of(new Alt(quads, reads, sources));
        }

        Node constant = SchemaIndex.singleValue(tc.valueExpr());
        if (constant != null) {
            Triple t = tripleFor(tc, subject, constant);
            if (t == null)
                return List.of();
            return List.of(new Alt(List.of(t), Set.of(), List.of(source(tc, scope, List.of(), List.of(), SourceKind.CONSTANT, null))));
        }

        if (tc.referencesShape()) {
            if (depth >= options.maxCallDepth) {
                fail(pred, null, null, "exceeded maxCallDepth", null);
                return List.of();
            }
            Node node = mint(tc, scope, depth);
            Triple link = tripleFor(tc, subject, node);
            if (link == null)
                return List.of();
            Source src = source(tc, scope, List.of(), List.of(), SourceKind.STRUCTURAL, null);
            List<Alt> out = new ArrayList<>();
            for (Alt n : shapeExpr(tc.valueExpr(), scope, node, depth + 1, null)) {
                if (skippable && n.reads.isEmpty() && (n.quads.isEmpty() || options.requireBindingsInSubshapes))
                    continue;                 // an optional island nothing asked for
                out.add(new Alt(List.of(link), Set.of(), List.of(src)).then(n));
            }
            return out;
        }

        fail(pred, null, null, "cannot synthesize " + (tc.valueExpr() == null ? "any value" : SchemaIndex.kindOf(tc.valueExpr()))
                + " without a Map action", null);
        return List.of();
    }

    /** A shape-valued constraint with {@code id(...)}: the node is a function of the key. */
    private List<Alt> keyed(Tc tc, List<String> acts, int scope, Node subject, int depth, boolean skippable) {
        String pred = tc.predicateStr();
        if (acts.size() > 1)
            throw new MaterializationException("id() must be the only Map code on the constraint at " + pred);
        if (!tc.referencesShape())
            throw new MaterializationException("id() at " + pred + " needs a shape-valued constraint: it names a node, not a value");
        String code = acts.get(0);
        List<MapFunctions.KeyTerm> terms = MapFunctions.keyTerms(code, index.prefixes());
        Set<Read> reads = new LinkedHashSet<>();
        List<String> staticsRead = new ArrayList<>();
        List<Integer> scopePath = tree.scope(scope).path;
        List<Node> values = new ArrayList<>();
        for (MapFunctions.KeyTerm term : terms) {
            Node value;
            if (term instanceof MapFunctions.NodeArgument) {
                value = tree.nearestNode(scope);
                if (value == null) {
                    fail(pred, null, code, "no @node in scope", null);
                    return List.of();
                }
            } else if (term instanceof MapFunctions.Template tpl) {
                String[] missing = { null };
                value = tpl.expand(v -> {
                    Node hit = get(scope, v, reads, staticsRead);
                    if (hit == null)
                        missing[0] = v;
                    return hit;
                });
                if (value == null) {
                    fail(pred, missing[0], code, null, scopePath);
                    return List.of();
                }
            } else {
                String v = ((MapFunctions.Variable) term).iri();
                value = get(scope, v, reads, staticsRead);
                if (value == null) {
                    fail(pred, v, code, null, scopePath);
                    return List.of();
                }
            }
            values.add(value);
        }
        Node node;
        if (values.size() == 1 && Terms.isResource(values.get(0))) {
            node = values.get(0);
        } else {
            String shape = tc.valueExpr() instanceof ShapeExprRef ref ? SchemaIndex.labelText(ref.getLabel()) : "inline" + tcOrdinal(tc);
            List<String> parts = new ArrayList<>();
            parts.add(shape);
            for (Node v : values)
                parts.add(Terms.n3(v));
            node = NodeFactory.createBlankNode("k" + Terms.digest(parts.toArray(new String[0])));
        }
        if (depth >= options.maxCallDepth) {
            fail(pred, null, null, "exceeded maxCallDepth", null);
            return List.of();
        }
        Triple link = tripleFor(tc, subject, node);
        if (link == null)
            return List.of();
        Source src = source(tc, scope, new ArrayList<>(reads), staticsRead, SourceKind.KEYED, code.strip());
        List<Alt> out = new ArrayList<>();
        for (Alt n : shapeExpr(tc.valueExpr(), scope, node, depth + 1, null)) {
            if (skippable && n.reads.isEmpty() && reads.isEmpty() && (n.quads.isEmpty() || options.requireBindingsInSubshapes))
                continue;
            out.add(new Alt(List.of(link), reads, List.of(src)).then(n));
        }
        return out;
    }

    private int tcOrdinal(Tc tc) {
        return tcIndex.computeIfAbsent(SchemaIndex.key(tc.tc()), k -> tcIndex.size());
    }

    // -- value expressions ----------------------------------------------------------------------

    /** {@code value} if it satisfies the constraint's value expression, a retyped copy of a plain
     * literal whose lexical form fits the expression's datatype, or null. */
    private Node checked(Tc tc, Node value) {
        ShapeExpr se = resolveSe(tc.valueExpr());
        if (se == null || !isNodeConstraint(se))
            return value;                     // nothing to check, or a shape: the nested materialization is the check
        if (satisfies(se, value))
            return value;
        String dt = datatypeOf(se);
        if (dt != null && Terms.isPlainLiteral(value)) {
            RDFDatatype type = Terms.datatype(dt);
            String lex = value.getLiteralLexicalForm();
            if (type.isValid(lex)) {
                Node retyped = NodeFactory.createLiteral(lex, type);
                if (satisfies(se, retyped))
                    return retyped;
            }
        }
        return null;
    }

    private ShapeExpr resolveSe(ShapeExpr se) {
        for (int i = 0; i < 100 && se instanceof ShapeExprRef ref; i++) {
            ShapeDecl decl = index.declaration(ref.getLabel());
            if (decl == null)
                return null;
            se = decl.getShapeExpr();
        }
        return se;
    }

    /** A value expression that constrains the node itself, not its arcs. */
    private boolean isNodeConstraint(ShapeExpr se) {
        if (se instanceof NodeConstraint)
            return true;
        if (se instanceof ShapeAnd and)
            return and.getShapeExprs().stream().anyMatch(p -> !(p instanceof ShapeExprRef) && isNodeConstraint(p));
        if (se instanceof ShapeOr or)
            return or.getShapeExprs().stream().anyMatch(p -> !(p instanceof ShapeExprRef) && isNodeConstraint(p));
        if (se instanceof ShapeNot not)
            return isNodeConstraint(not.getShapeExpr());
        return false;
    }

    /** The datatype a value expression asks for: its own, or the one conjunct that has one. */
    private String datatypeOf(ShapeExpr se) {
        se = resolveSe(se);
        if (se instanceof NodeConstraint nc) {
            String found = null;
            for (NodeConstraintComponent c : nc.getComponents())
                if (c instanceof DatatypeConstraint d)
                    found = found == null ? d.getDatatypeURI() : null;
            return found;
        }
        if (se instanceof ShapeAnd and) {
            Set<String> found = new LinkedHashSet<>();
            for (ShapeExpr p : and.getShapeExprs()) {
                String d = datatypeOf(p);
                if (d != null)
                    found.add(d);
            }
            return found.size() == 1 ? found.iterator().next() : null;
        }
        return null;
    }

    private boolean satisfies(ShapeExpr se, Node value) {
        se = resolveSe(se);
        if (se == null)
            return true;
        if (se instanceof NodeConstraint)
            return ShapeExprEval.satisfies(value, se, checkCxt, ValidateOnlyReporter.factory());
        if (se instanceof ShapeAnd and) {
            for (ShapeExpr p : and.getShapeExprs()) {
                ShapeExpr r = resolveSe(p);
                if (r != null && isNodeConstraint(r) && !satisfies(p, value))
                    return false;
            }
            return true;
        }
        if (se instanceof ShapeOr or) {
            for (ShapeExpr p : or.getShapeExprs())
                if (satisfies(p, value))
                    return true;
            return false;
        }
        if (se instanceof ShapeNot not)
            return !satisfies(not.getShapeExpr(), value);
        return true;                          // a shape: the nested materialization is the check
    }

    /** The node for a shape-valued constraint without a key: determined by the root, the
     * constraint, its call depth, and the scope -- the input node the scope matched when it is an
     * iteration, else its path -- so a materialization is reproducible. */
    private Node mint(Tc tc, int scope, int depth) {
        int n = tcOrdinal(tc);
        Scope s = tree.scope(scope);
        StringBuilder where = new StringBuilder();
        if (s.node != null) {
            where.append(Terms.n3(s.node));
        } else {
            where.append('p');
            for (int i = 0; i < s.path.size(); i++)
                where.append(i == 0 ? "" : "_").append(s.path.get(i));
        }
        return NodeFactory.createBlankNode("m" + Terms.digest(Terms.n3(root), String.valueOf(n), String.valueOf(depth), where.toString()));
    }

    // -- combining alternatives -----------------------------------------------------------------

    /** Every combination of one alternative per part, pruned as it grows. */
    private List<Alt> all(List<List<Alt>> parts) {
        List<Alt> acc = new ArrayList<>();
        acc.add(EMPTY);
        for (List<Alt> alternatives : parts) {
            if (alternatives.isEmpty())
                return List.of();
            List<Alt> next = new ArrayList<>();
            for (Alt a : acc)
                for (Alt b : alternatives)
                    next.add(a.then(b));
            acc = prune(next);
        }
        return acc;
    }

    private List<Alt> prune(List<Alt> results) {
        if (results.size() <= options.maxAccepts)
            return new ArrayList<>(results);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < results.size(); i++)
            order.add(i);
        order.sort((i, j) -> {
            int c = Integer.compare(results.get(j).rankReads(), results.get(i).rankReads());
            if (c == 0) c = Integer.compare(results.get(j).rankQuads(), results.get(i).rankQuads());
            if (c == 0) c = Integer.compare(i, j);
            return c;
        });
        List<Integer> kept = new ArrayList<>(order.subList(0, options.maxAccepts));
        dropped += results.size() - kept.size();
        java.util.Collections.sort(kept);
        List<Alt> out = new ArrayList<>();
        for (int i : kept)
            out.add(results.get(i));
        return out;
    }

    // -- which list a repetition iterates -------------------------------------------------------

    private List<Integer> listPath(TripleExpr expr) {
        int k = SchemaIndex.key(expr);
        if (listsComputed.contains(k))
            return lists.get(k);
        listsComputed.add(k);
        lists.put(k, null);                   // a recursive repetition counts as scope-free while computed
        List<Integer> found = analyse(expr);
        lists.put(k, found);
        return found;
    }

    /** The list the repetition {@code expr} iterates: the deepest list its body's variables are
     * bound at, nested repetitions counting through their parent list. */
    private List<Integer> analyse(TripleExpr expr) {
        Set<String> direct = new LinkedHashSet<>();
        List<TripleExpr> nested = new ArrayList<>();
        collect(expr, direct, nested, new HashSet<>(), true);
        Map<List<Integer>, String> candidates = new LinkedHashMap<>();
        for (String v : direct) {
            List<Integer> lp = tree.boundAt().get(v);
            if (lp != null)
                candidates.putIfAbsent(lp, v);
        }
        for (TripleExpr r : nested) {
            List<Integer> lp = listPath(r);
            if (lp != null && !lp.isEmpty())
                candidates.putIfAbsent(lp.subList(0, lp.size() - 1), "the repetition at " + index.labelOf(r));
        }
        if (candidates.isEmpty())
            return null;
        List<Integer> deepest = null;
        for (List<Integer> lp : candidates.keySet())
            if (deepest == null || lp.size() > deepest.size())
                deepest = lp;
        for (Map.Entry<List<Integer>, String> e : candidates.entrySet())
            if (!ScopeTree.isPrefix(e.getKey(), deepest))
                throw new MaterializationException("the repetition at " + index.labelOf(expr) + " reads from unrelated lists: "
                        + e.getValue() + " is bound at " + e.getKey() + " and " + candidates.get(deepest) + " at " + deepest);
        return deepest;
    }

    /** Variables read directly in {@code expr}'s body and the repetitions directly inside it. */
    private void collect(TripleExpr expr, Set<String> direct, List<TripleExpr> nested, Set<Integer> seen, boolean top) {
        expr = index.resolve(expr);
        if (!seen.add(SchemaIndex.key(expr)))
            return;
        if (!top && SchemaIndex.isRepetition(expr)) {
            nested.add(expr);
            return;
        }
        Tc tc = Tc.of(expr);
        if (tc != null) {
            for (SemAct act : tc.mapActions()) {
                try {
                    for (String v : MapFunctions.codeVariables(SchemaIndex.codeOf(act), index.prefixes()))
                        if (!v.equals(MapFunctions.NODE_ARGUMENT))
                            direct.add(v);
                } catch (MapFunctionException e) {
                    // a malformed code fails where it is evaluated
                }
            }
            if (tc.referencesShape())
                collectShape(tc.valueExpr(), direct, nested, seen);
        } else {
            for (TripleExpr e : SchemaIndex.subExpressions(expr))
                collect(e, direct, nested, seen, false);
        }
    }

    private void collectShape(ShapeExpr se, Set<String> direct, List<TripleExpr> nested, Set<Integer> seen) {
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

    // -- convenience ----------------------------------------------------------------------------

    /** Materialize {@code root} as an instance of the output schema from {@code bindings}, into a
     * new graph carrying the schema's prefixes. */
    public static Graph materialize(ShexSchema schema, Bindings bindings, Node root, Node start, Options options) {
        Materializer m = new Materializer(schema, options == null ? new Options() : options);
        Graph graph = GraphFactory.createDefaultGraph();
        for (Map.Entry<String, String> e : m.prefixes().entrySet())
            graph.getPrefixMapping().setNsPrefix(e.getKey(), e.getValue());
        for (Triple t : m.materialize(bindings.tree(), root, start))
            graph.add(t);
        return graph;
    }

    /** {@link Function} form of a read, for tests and tools. */
    static Function<String, Node> reader(Map<String, Node> frame) {
        return frame::get;
    }
}
