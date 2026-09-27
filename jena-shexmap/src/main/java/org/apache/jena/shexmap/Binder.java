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
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.shex.ShapeDecl;
import org.apache.jena.shex.ShexSchema;
import org.apache.jena.shex.ShexStatus;
import org.apache.jena.shex.expressions.Cardinality;
import org.apache.jena.shex.expressions.EachOf;
import org.apache.jena.shex.expressions.OneOf;
import org.apache.jena.shex.expressions.SemAct;
import org.apache.jena.shex.expressions.Shape;
import org.apache.jena.shex.expressions.ShapeAnd;
import org.apache.jena.shex.expressions.ShapeExpr;
import org.apache.jena.shex.expressions.ShapeExprRef;
import org.apache.jena.shex.expressions.ShapeOr;
import org.apache.jena.shex.expressions.TripleExpr;
import org.apache.jena.shex.reporting.Report;
import org.apache.jena.shex.reporting.ValidateOnlyReporter;
import org.apache.jena.shex.semact.SemanticActionPlugin;
import org.apache.jena.shex.sys.SysShex;
import org.apache.jena.shex.validation.ShapeExprEval;
import org.apache.jena.shex.validation.ValidationContext;
import org.apache.jena.shexmap.SchemaIndex.Tc;

/** Collect ShExMap bindings from RDF that conforms to an input schema.
 * <p>
 * Jena's validator says whether a node conforms but not which triples each constraint took, so
 * after it accepts the focus the neighbourhood of each node is partitioned again here, by an
 * exhaustive search in schema order (larger matches first), the validator judging the values of
 * shape-valued constraints.  When several partitions -- or several ways to satisfy nested shapes
 * -- lead to different bindings, the input schema is ambiguous for mapping: {@link #bind} returns
 * the first and records how many there were, {@link #bindAll} returns them all, and {@code strict}
 * turns ambiguity into an error.  Which parse is first follows the order the neighbourhood's
 * triples are tried in: by predicate, then by value, blank nodes ordered by the arcs they carry
 * rather than by the labels the parser gave them, so two runs over the same document agree.
 * <p>
 * EXTENDS is followed: a shape's neighbourhood is shared between its own expression and the shapes
 * it extends, and a reference to a shape with extensions binds through the most specific extension
 * the node satisfies.  Inverse triple constraints ({@code ^p}) bind the subject of the matched
 * triple. */
public final class Binder {
    /** Distinct binding trees to collect before giving up counting. */
    public static final int MAX_ALTERNATIVES = 20;
    /** Partitions of one neighbourhood to try. */
    public static final int MAX_PARTITIONS = 10_000;

    private final SchemaIndex index;
    private final Graph graph;
    private final ValidationContext vCxt;
    private final int limit;
    private final Set<String> active = new HashSet<>();
    private final Map<String, Boolean> satCache = new HashMap<>();
    private final Map<String, String> keyCache = new HashMap<>();

    private Binder(SchemaIndex index, Graph graph, ValidationContext vCxt, int limit) {
        this.index = index;
        this.graph = graph;
        this.vCxt = vCxt;
        this.limit = Math.max(1, limit);
    }

    // -- records ------------------------------------------------------------------------------

    /** One scope while collecting: the bindings made while matching one node, one list of
     * iteration records per repeated constraint or group that matched under it, and the triples
     * matched here. */
    static final class Record {
        final Map<String, Node> vars = new LinkedHashMap<>();
        final List<List<Record>> lists = new ArrayList<>();
        final Set<Triple> triples = new LinkedHashSet<>();

        Record merged(Record other) {
            Record r = new Record();
            r.vars.putAll(vars);
            r.vars.putAll(other.vars);
            r.lists.addAll(lists);
            r.lists.addAll(other.lists);
            r.triples.addAll(triples);
            r.triples.addAll(other.triples);
            return r;
        }

        Set<Triple> allTriples() {
            Set<Triple> out = new LinkedHashSet<>(triples);
            for (List<Record> l : lists)
                for (Record r : l)
                    out.addAll(r.allTriples());
            return out;
        }

        boolean isEmpty() {
            for (String k : vars.keySet())
                if (!k.equals(BindingTree.NODE_KEY))
                    return false;
            for (List<Record> l : lists)
                for (Record r : l)
                    if (!r.isEmpty())
                        return false;
            return true;
        }

        BindingTree tree() {
            List<List<BindingTree>> ls = new ArrayList<>();
            for (List<Record> l : lists) {
                List<BindingTree> its = new ArrayList<>();
                for (Record r : l)
                    its.add(r.tree());
                ls.add(its);
            }
            return new BindingTree(vars, ls);
        }
    }

    /** A triple of the neighbourhood, with the direction it was reached in. */
    private record Dt(boolean inverse, Triple triple) {
        Node value() { return inverse ? triple.getSubject() : triple.getObject(); }
    }

    private sealed interface Item permits TcItem, Group {}
    /** A triple constraint matched one triple (an index into the neighbourhood). */
    private record TcItem(Tc tc, int at) implements Item {}
    /** One iteration of a repeated group. */
    private record Group(TripleExpr expr, List<Item> items) implements Item {}

    /** What the enumerations hand each partition to; returns whether to go on. */
    private interface Visit {
        boolean visit(List<Item> items, BitSet rest);
    }

    // -- the graph ------------------------------------------------------------------------------

    private List<Triple> outgoing(Node n) {
        List<Triple> out = new ArrayList<>();
        if (!Terms.isResource(n))
            return out;
        graph.find(n, Node.ANY, Node.ANY).forEachRemaining(out::add);
        return out;
    }

    private List<Triple> incoming(Node n) {
        List<Triple> out = new ArrayList<>();
        graph.find(Node.ANY, Node.ANY, n).forEachRemaining(out::add);
        return out;
    }

    /** The order candidates are tried in: IRIs and literals by their N3 form, blank nodes by the
     * arcs they carry (to {@code depth} levels), so that the order does not depend on the labels
     * the parser gave them. */
    private String sortKey(Node value, int depth) {
        if (!value.isBlank())
            return Terms.n3(value);
        if (depth == 0)
            return "_:";
        String cacheKey = value.getBlankNodeLabel() + "|" + depth;
        String hit = keyCache.get(cacheKey);
        if (hit != null)
            return hit;
        List<String> arcs = new ArrayList<>();
        for (Triple t : outgoing(value))
            arcs.add("<" + t.getPredicate().getURI() + "> " + sortKey(t.getObject(), depth - 1));
        java.util.Collections.sort(arcs);
        String k = "_:[" + String.join("; ", arcs) + "]";
        keyCache.put(cacheKey, k);
        return k;
    }

    // -- conformance --------------------------------------------------------------------------

    /** Whether {@code n} satisfies a shape expression, by Jena's validator. */
    boolean satisfies(Node n, ShapeExpr se) {
        String k = Terms.n3(n) + "|" + se.id;
        Boolean hit = satCache.get(k);
        if (hit != null)
            return hit;
        boolean result = ShapeExprEval.satisfies(n, se, vCxt, ValidateOnlyReporter.factory());
        satCache.put(k, result);
        return result;
    }

    // -- shape expressions: each returns the alternative records for node n -------------------

    List<Record> shapeExpr(Node n, ShapeExpr se, String label) {
        if (se instanceof ShapeExprRef ref) {
            ShapeDecl decl = index.declaration(ref.getLabel());
            if (decl == null)
                return List.of(new Record());
            String text = SchemaIndex.labelText(ref.getLabel());
            for (ShapeDecl candidate : index.extensionCandidates(decl))
                if (candidate == decl || satisfies(n, candidate.getShapeExpr()))
                    return guarded(n, candidate.getShapeExpr(), text);
            return List.of(new Record());
        }
        return guarded(n, se, label);
    }

    private List<Record> guarded(Node n, ShapeExpr se, String label) {
        String k = Terms.n3(n) + "|" + se.id;
        if (!active.add(k))
            return List.of(new Record());     // recursion: the outer match already collects these
        try {
            return shapeExprInner(n, se, label);
        } finally {
            active.remove(k);
        }
    }

    private List<Record> shapeExprInner(Node n, ShapeExpr se, String label) {
        if (se instanceof Shape shape) {
            List<Record> records = partitions(n, shape);
            if (records.isEmpty())
                // validation accepted the node, so a partition exists: either the search gave up
                // (MAX_PARTITIONS) or the partitioner disagrees with the validator.  Say so, rather
                // than binding nothing here and handing back a hollow tree.
                throw new MapValidationException("no partition of the neighbourhood of " + Terms.n3(n)
                        + " matches shape " + (label == null ? "(inline)" : label));
            return records;
        }
        if (se instanceof ShapeAnd and) {
            List<List<Record>> alternatives = new ArrayList<>();
            for (ShapeExpr part : and.getShapeExprs())
                alternatives.add(shapeExpr(n, part, label));
            return dedupe(product(alternatives, limit), limit);
        }
        if (se instanceof ShapeOr or) {
            for (ShapeExpr part : or.getShapeExprs())
                if (satisfies(n, part))
                    return shapeExpr(n, part, label);
            return List.of(new Record());
        }
        return List.of(new Record());     // NodeConstraint, ShapeNot, ShapeExternal bind nothing
    }

    // -- shapes: partition the neighbourhood, then bind each partition --------------------------

    private List<Record> partitions(Node n, Shape shape) {
        List<Shape> parts = index.shapeParts(shape);
        List<Tc> tcs = new ArrayList<>();
        for (Shape p : parts)
            tcs.addAll(index.ownTcs(p));
        Set<String> keys = new HashSet<>();
        for (Tc tc : tcs)
            keys.add(tc.predicate().getURI() + (tc.inverse() ? "^" : ""));
        Set<Node> extras = new HashSet<>();
        for (Shape p : parts)
            extras.addAll(p.getExtras());

        List<Object[]> keyed = new ArrayList<>();   // (dirKey, predicate, sortKey, Dt)
        for (Triple t : outgoing(n))
            if (keys.contains(t.getPredicate().getURI()))
                keyed.add(new Object[] { "0", t.getPredicate().getURI(), sortKey(t.getObject(), 2), new Dt(false, t) });
        if (keys.stream().anyMatch(k -> k.endsWith("^")))
            for (Triple t : incoming(n))
                if (keys.contains(t.getPredicate().getURI() + "^"))
                    keyed.add(new Object[] { "1", t.getPredicate().getURI(), sortKey(t.getSubject(), 2), new Dt(true, t) });
        keyed.sort((a, b) -> {
            int c = ((String) a[0]).compareTo((String) b[0]);
            if (c == 0) c = ((String) a[1]).compareTo((String) b[1]);
            if (c == 0) c = ((String) a[2]).compareTo((String) b[2]);
            return c;
        });
        List<Dt> matchables = new ArrayList<>();
        for (Object[] k : keyed) {
            Dt dt = (Dt) k[3];
            if (matchables.isEmpty() || !matchables.get(matchables.size() - 1).equals(dt))
                matchables.add(dt);
        }

        List<TripleExpr> exprs = new ArrayList<>();
        for (Shape p : parts)
            if (p.getTripleExpr() != null && !(p.getTripleExpr() instanceof org.apache.jena.shex.expressions.TripleExprEmpty))
                exprs.add(p.getTripleExpr());
        List<TripleExpr> repeated = index.repeatedExpressions(exprs);
        BitSet all = new BitSet();
        all.set(0, matchables.size());
        List<Record> results = new ArrayList<>();
        int[] tried = { 0 };
        parts(n, exprs, all, matchables, (items, rest) -> {
            if (++tried[0] > MAX_PARTITIONS)
                return false;
            if (!validRemainder(rest, matchables, extras, tcs))
                return true;
            results.addAll(bindItems(items, repeated, matchables));
            List<Record> kept = dedupe(results, limit);
            results.clear();
            results.addAll(kept);
            return results.size() < limit;
        });
        return results;
    }

    private boolean validRemainder(BitSet rest, List<Dt> m, Set<Node> extras, List<Tc> tcs) {
        for (int i = rest.nextSetBit(0); i >= 0; i = rest.nextSetBit(i + 1)) {
            Dt dt = m.get(i);
            if (dt.inverse() || !extras.contains(dt.triple().getPredicate()))
                return false;
            // EXTRA may only absorb a triple that no constraint on its predicate accepts
            for (Tc tc : tcs)
                if (!tc.inverse() && tc.predicate().equals(dt.triple().getPredicate())
                        && (tc.valueExpr() == null || satisfies(dt.triple().getObject(), tc.valueExpr())))
                    return false;
        }
        return true;
    }

    /** Partitions of {@code avail} among a sequence of triple expressions. */
    private boolean parts(Node n, List<TripleExpr> exprs, BitSet avail, List<Dt> m, Visit visit) {
        if (exprs.isEmpty())
            return visit.visit(List.of(), avail);
        TripleExpr first = exprs.get(0);
        List<TripleExpr> restExprs = exprs.subList(1, exprs.size());
        return matchExpr(n, first, avail, m, (items, rest) ->
            parts(n, restExprs, rest, m, (more, rest2) -> {
                List<Item> all = new ArrayList<>(items);
                all.addAll(more);
                return visit.visit(all, rest2);
            }));
    }

    /** Ways {@code expr} can match some of {@code avail}, greediest first. */
    private boolean matchExpr(Node n, TripleExpr expr, BitSet avail, List<Dt> m, Visit visit) {
        expr = index.resolve(expr);
        Tc tc = Tc.of(expr);
        Cardinality card = SchemaIndex.cardinality(expr);
        if (tc != null) {
            List<Integer> candidates = new ArrayList<>();
            for (int i = avail.nextSetBit(0); i >= 0; i = avail.nextSetBit(i + 1)) {
                Dt dt = m.get(i);
                if (dt.inverse() != tc.inverse() || !dt.triple().getPredicate().equals(tc.predicate()))
                    continue;
                if (tc.valueExpr() == null || satisfies(dt.value(), tc.valueExpr()))
                    candidates.add(i);
            }
            int upper = Math.min(card.max, candidates.size());
            if (upper < card.min)
                return true;
            for (int size = upper; size >= card.min; size--) {
                int[] combo = new int[size];
                for (int i = 0; i < size; i++)
                    combo[i] = i;
                boolean more = size <= candidates.size();
                while (more) {
                    List<Item> items = new ArrayList<>();
                    BitSet rest = (BitSet) avail.clone();
                    for (int c : combo) {
                        items.add(new TcItem(tc, candidates.get(c)));
                        rest.clear(candidates.get(c));
                    }
                    if (!visit.visit(items, rest))
                        return false;
                    more = nextCombination(combo, candidates.size());
                }
            }
            return true;
        }
        return repeat(n, expr, avail, m, 0, card.min, card.max, SchemaIndex.isRepeated(expr), visit);
    }

    /** Advances to the next k-subset of {@code 0..n} in lexicographic order; false at the last. */
    private static boolean nextCombination(int[] combo, int n) {
        int k = combo.length;
        int i = k - 1;
        while (i >= 0 && combo[i] == n - k + i)
            i--;
        if (i < 0)
            return false;
        combo[i]++;
        for (int j = i + 1; j < k; j++)
            combo[j] = combo[j - 1] + 1;
        return true;
    }

    private boolean repeat(Node n, TripleExpr expr, BitSet avail, List<Dt> m, int count, int min, int max, boolean repeated, Visit visit) {
        if (count < max) {
            boolean goOn = once(n, expr, avail, m, (one, rest) -> {
                if (rest.equals(avail) && count >= min)
                    return true;    // an iteration that matches nothing adds nothing
                List<Item> wrapped = repeated ? List.of(new Group(expr, one)) : one;
                if (rest.equals(avail))
                    return visit.visit(wrapped, rest);   // matched nothing but is required: count it once
                return repeat(n, expr, rest, m, count + 1, min, max, repeated, (more, rest2) -> {
                    List<Item> all = new ArrayList<>(wrapped);
                    all.addAll(more);
                    return visit.visit(all, rest2);
                });
            });
            if (!goOn)
                return false;
        }
        if (count >= min)
            return visit.visit(List.of(), avail);
        return true;
    }

    private boolean once(Node n, TripleExpr expr, BitSet avail, List<Dt> m, Visit visit) {
        TripleExpr in = SchemaIndex.inner(expr);
        if (in instanceof EachOf)
            return parts(n, SchemaIndex.subExpressions(expr), avail, m, visit);
        if (in instanceof OneOf) {
            for (TripleExpr sub : SchemaIndex.subExpressions(expr))
                if (!matchExpr(n, sub, avail, m, visit))
                    return false;
            return true;
        }
        return matchExpr(n, in, avail, m, visit);
    }

    // -- binding a partition ------------------------------------------------------------------

    /** Alternative records for one partition's items: each of the scope's {@code repeated}
     * expressions makes one list, with an iteration per item (empty when it matched nothing); a
     * non-repeated constraint binds into the scope itself. */
    private List<Record> bindItems(List<Item> items, List<TripleExpr> repeated, List<Dt> m) {
        Map<Integer, List<Item>> byExpr = new LinkedHashMap<>();
        List<TripleExpr> firsts = new ArrayList<>();
        List<List<Record>> alternatives = new ArrayList<>();
        for (Item item : items) {
            TripleExpr expr;
            boolean grouped;
            if (item instanceof Group g) {
                expr = g.expr();
                grouped = true;
            } else {
                TcItem ti = (TcItem) item;
                expr = ti.tc().expr();
                grouped = SchemaIndex.isRepeated(expr);
            }
            if (grouped) {
                int k = SchemaIndex.key(expr);
                if (!byExpr.containsKey(k))
                    firsts.add(expr);
                byExpr.computeIfAbsent(k, x -> new ArrayList<>()).add(item);
                continue;
            }
            TcItem ti = (TcItem) item;
            Triple t = m.get(ti.at()).triple();
            Node value = m.get(ti.at()).value();
            Map<String, Node> lifted = lift(ti.tc(), value);
            if (ti.tc().referencesShape()) {
                // a nested scope: merge it into this one
                List<Record> opts = new ArrayList<>();
                for (Record sub : shapeExpr(value, ti.tc().valueExpr(), null)) {
                    Record r = new Record();
                    boolean clash = lifted.keySet().stream().anyMatch(sub.vars::containsKey);
                    r.vars.putAll(lifted);
                    if (clash) {                    // a name clash: keep it apart
                        r.lists.add(List.of(sub));
                    } else {
                        r.vars.putAll(sub.vars);
                        r.lists.addAll(sub.lists);
                        r.triples.addAll(sub.triples);
                    }
                    r.triples.add(t);
                    opts.add(r);
                }
                alternatives.add(opts);
            } else {
                Record r = new Record();
                r.vars.putAll(lifted);
                r.triples.add(t);
                alternatives.add(List.of(r));
            }
        }
        List<TripleExpr> order = new ArrayList<>(repeated);
        for (TripleExpr e : firsts)
            if (order.stream().noneMatch(o -> SchemaIndex.key(o) == SchemaIndex.key(e)))
                order.add(e);
        for (TripleExpr expr : order) {
            List<Item> its = byExpr.getOrDefault(SchemaIndex.key(expr), List.of());
            List<List<Record>> perIteration = new ArrayList<>();
            for (Item it : its)
                perIteration.add(iteration(it, m));
            List<Record> opts = new ArrayList<>();
            int[] counters = new int[perIteration.size()];
            while (true) {
                Record r = new Record();
                List<Record> kept = new ArrayList<>();
                for (int i = 0; i < perIteration.size(); i++) {
                    Record c = perIteration.get(i).get(counters[i]);
                    if (c.isEmpty())
                        r.triples.addAll(c.triples);   // an iteration that bound nothing still matched
                    else
                        kept.add(c);
                }
                r.lists.add(kept);
                opts.add(r);
                if (opts.size() >= limit)
                    break;
                int i = perIteration.size() - 1;
                while (i >= 0) {
                    counters[i]++;
                    if (counters[i] < perIteration.get(i).size())
                        break;
                    counters[i] = 0;
                    i--;
                }
                if (i < 0)
                    break;
            }
            alternatives.add(opts);
        }
        return product(alternatives, limit);
    }

    /** Alternative scopes for one iteration of a repeated constraint or group. */
    private List<Record> iteration(Item item, List<Dt> m) {
        if (item instanceof Group g) {
            List<Record> found = bindItems(g.items(), index.repeatedExpressions(SchemaIndex.subExpressions(g.expr())), m);
            return found.isEmpty() ? List.of(new Record()) : found;
        }
        TcItem ti = (TcItem) item;
        Triple t = m.get(ti.at()).triple();
        Node value = m.get(ti.at()).value();
        Map<String, Node> lifted = lift(ti.tc(), value);
        if (ti.tc().referencesShape()) {
            List<Record> out = new ArrayList<>();
            for (Record sub : shapeExpr(value, ti.tc().valueExpr(), null)) {
                Record r = new Record();
                r.vars.put(BindingTree.NODE_KEY, value);
                r.vars.putAll(lifted);
                r.vars.putAll(sub.vars);
                r.lists.addAll(sub.lists);
                r.triples.addAll(sub.triples);
                r.triples.add(t);
                out.add(r);
            }
            return out;
        }
        Record r = new Record();
        r.vars.putAll(lifted);
        r.triples.add(t);
        return List.of(r);
    }

    private Map<String, Node> lift(Tc tc, Node value) {
        Map<String, Node> bound = new LinkedHashMap<>();
        for (SemAct act : tc.mapActions()) {
            String code = SchemaIndex.codeOf(act);
            if (MapFunctions.isFunctionCall(code))
                bound.putAll(MapFunctions.lift(code, value, index.prefixes()));
            else
                bound.put(MapFunctions.expandVariable(code, index.prefixes()), value);
        }
        return bound;
    }

    // -- combining ------------------------------------------------------------------------------

    private static List<Record> product(List<List<Record>> alternatives, int limit) {
        List<Record> acc = new ArrayList<>();
        acc.add(new Record());
        for (List<Record> alts : alternatives) {
            if (alts.isEmpty())
                return new ArrayList<>();
            List<Record> next = new ArrayList<>();
            outer:
            for (Record a : acc)
                for (Record b : alts) {
                    next.add(a.merged(b));
                    if (next.size() >= limit)
                        break outer;
                }
            acc = next;
        }
        return acc;
    }

    private static List<Record> dedupe(List<Record> records, int limit) {
        Set<String> seen = new HashSet<>();
        List<Record> out = new ArrayList<>();
        for (Record r : records) {
            if (seen.add(r.tree().canonical())) {
                out.add(r);
                if (out.size() >= limit)
                    break;
            }
        }
        return out;
    }

    // -- entry points ---------------------------------------------------------------------------

    /** A validation context for binding: the schema on the graph, with the Map plugin so that
     * {@code regex()} and {@code hashmap()} codes take part in validation. */
    static ValidationContext context(ShexSchema schema, Graph graph, Map<String, SemanticActionPlugin> plugins) {
        Map<String, SemanticActionPlugin> all = new HashMap<>(MapSemanticActionPlugin.index());
        if (plugins != null)
            all.putAll(plugins);
        return new ValidationContext(schema, graph, all, true, true);
    }

    /** Every distinct binding tree the input allows for {@code focus} (up to {@code limit}).
     *
     * @param graph the input graph
     * @param schema the input schema (its prefixes name the ShExMap variables)
     * @param focus the node to start from
     * @param start the shape label to match against; the schema's start when null
     * @param limit how many distinct trees to collect
     * @param plugins semantic-action plugins beside the Map one, or null
     * @throws MapValidationException when {@code focus} does not conform */
    public static List<Bindings> bindAll(Graph graph, ShexSchema schema, Node focus, Node start, int limit,
                                         Map<String, SemanticActionPlugin> plugins) {
        SchemaIndex index = new SchemaIndex(schema);
        ValidationContext vCxt = context(schema, graph, plugins);
        Node label = start == null ? SysShex.startNode : start;
        if (index.declaration(label) == null)
            throw new ShExMapException("the schema does not define " + SchemaIndex.labelText(label));
        Report report = vCxt.validate(focus, label, ValidateOnlyReporter.factory());
        if (report == null || report.getStatus() != ShexStatus.conformant)
            throw new MapValidationException(Terms.n3(focus) + " does not conform to " + SchemaIndex.labelText(label));
        Binder binder = new Binder(index, graph, vCxt, limit);
        ShapeExpr startExpr = index.startExpr(start);
        List<Record> records = binder.shapeExpr(focus, startExpr, start == null ? "START" : SchemaIndex.labelText(start));
        List<Record> kept = dedupe(records, limit);
        List<Bindings> out = new ArrayList<>();
        for (Record r : kept)
            out.add(new Bindings(r.tree(), kept.size(), r.allTriples()));
        return out;
    }

    /** Validate {@code focus} against {@code schema} and collect its ShExMap bindings.
     * <p>
     * When the input conforms in several ways that bind differently, the first (in schema order,
     * larger matches first) is returned and {@link Bindings#alternatives()} says how many there
     * were; with {@code strict} an {@link AmbiguousBindingsException} is thrown instead. */
    public static Bindings bind(Graph graph, ShexSchema schema, Node focus, Node start, boolean strict,
                                Map<String, SemanticActionPlugin> plugins) {
        List<Bindings> found = bindAll(graph, schema, focus, start, MAX_ALTERNATIVES, plugins);
        if (strict && found.size() > 1)
            throw new AmbiguousBindingsException(Terms.n3(focus) + " matches the input schema in " + found.size()
                    + " ways that bind different values", found);
        return found.get(0);
    }
}
