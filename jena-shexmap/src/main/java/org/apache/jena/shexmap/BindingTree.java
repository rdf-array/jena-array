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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.Node;

/** A scope of a binding tree: its own bindings ({@code @node} included, when recorded) and one
 * list of iterations per repeated expression.
 * <p>
 * The tree uses the structure shex.js prints and reads.  A <em>scope</em> (what one node's match
 * binds) is an object mapping variable IRIs to terms, or, when the match has repeated parts, an
 * array whose first element is that object (possibly empty) and whose other elements are
 * <em>lists</em>; a list has one element per iteration of a repeated constraint or group, each
 * element a scope, so everything bound from one reading of a blood pressure stays together.  A
 * list's elements are uniform: all objects, or all arrays (a lone object among arrays is written
 * as {@code [object]}).  Every repeated constraint or group of the shape gets its list, in schema
 * order, empty when nothing matched.  An element made by a repeated <em>shape-valued</em>
 * constraint also records the node the nested shape matched under the reserved key
 * {@code "@node"} ({@link #NODE_KEY}; the subject for an inverse constraint).  It is not a
 * variable: {@link #frames} leaves it out and no output constraint can read it. */
public final class BindingTree {
    /** In an iteration's bindings: the node the nested shape matched. */
    public static final String NODE_KEY = "@node";

    private final Map<String, Node> own;
    private final List<List<BindingTree>> lists;

    public BindingTree(Map<String, Node> own, List<List<BindingTree>> lists) {
        this.own = new LinkedHashMap<>(own);
        this.lists = new ArrayList<>();
        for (List<BindingTree> l : lists)
            this.lists.add(new ArrayList<>(l));
    }

    public BindingTree(Map<String, Node> own) {
        this(own, List.of());
    }

    /** The variables bound here, and {@code @node} when recorded. */
    public Map<String, Node> own() { return own; }

    /** One list of iteration scopes per repeated expression. */
    public List<List<BindingTree>> lists() { return lists; }

    /** The node this iteration matched, or null. */
    public Node node() { return own.get(NODE_KEY); }

    // -- JSON -----------------------------------------------------------------------------------

    /** The tree as shex.js's JSON: the object alone, or {@code [object, list, ...]}, each list's
     * elements uniform (all objects or all arrays). */
    public JsonValue toJson() {
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, Node> e : own.entrySet())
            obj.put(e.getKey(), Terms.toJson(e.getValue()));
        if (lists.isEmpty())
            return obj;
        JsonArray arr = new JsonArray();
        arr.add(obj);
        for (List<BindingTree> list : lists) {
            boolean wrap = list.stream().anyMatch(it -> !it.lists.isEmpty());
            JsonArray items = new JsonArray();
            for (BindingTree it : list) {
                JsonValue j = it.toJson();
                if (wrap && !j.isArray()) {
                    JsonArray one = new JsonArray();
                    one.add(j);
                    j = one;
                }
                items.add(j);
            }
            arr.add(items);
        }
        return arr;
    }

    /** A tree from JSON, accepting the two older layouts shex.js and PyShEx wrote: a root without
     * own bindings written as its one list, and a nested scope written as a sibling. */
    public static BindingTree fromJson(JsonValue value) {
        if (value.isObject())
            return scopeFromJson(value);
        if (value.isArray()) {
            JsonArray a = value.getAsArray();
            if (isScopeArray(a))
                return scopeFromJson(value);
            if (allMatch(a, JsonValue::isObject))       // L1: shex.js's root, written as its one list
                return new BindingTree(Map.of(), List.of(iterationsFromJson(a)));
            if (allMatch(a, JsonValue::isArray)) {
                if (allMatch(a, e -> isScopeArray(e.getAsArray())))
                    return new BindingTree(Map.of(), List.of(iterationsFromJson(a)));
                if (allMatch(a, e -> allMatch(e.getAsArray(), JsonValue::isObject))) {
                    // PyShEx before 2026-09-27: the root's lists without its object
                    List<List<BindingTree>> lists = new ArrayList<>();
                    for (JsonValue e : a)
                        lists.add(iterationsFromJson(e.getAsArray()));
                    return new BindingTree(Map.of(), lists);
                }
            }
        }
        throw new BindingTreeException("not a scope: " + shortText(value));
    }

    private static boolean allMatch(JsonArray a, java.util.function.Predicate<JsonValue> p) {
        for (JsonValue v : a)
            if (!p.test(v))
                return false;
        return true;
    }

    private static boolean isScopeArray(JsonArray a) {
        if (a.size() == 0 || !a.get(0).isObject())
            return false;
        for (int i = 1; i < a.size(); i++)
            if (!a.get(i).isArray())
                return false;
        return true;
    }

    private static BindingTree scopeFromJson(JsonValue value) {
        JsonObject obj;
        List<JsonValue> rest = new ArrayList<>();
        if (value.isObject()) {
            obj = value.getAsObject();
        } else {
            JsonArray a = value.getAsArray();
            if (a.size() == 0 || !a.get(0).isObject())
                throw new BindingTreeException("not a scope: " + shortText(value));
            obj = a.get(0).getAsObject();
            for (int i = 1; i < a.size(); i++)
                rest.add(a.get(i));
        }
        Map<String, Node> own = new LinkedHashMap<>();
        for (String k : obj.keys())
            own.put(k, Terms.fromJson(obj.get(k)));
        List<List<BindingTree>> lists = new ArrayList<>();
        for (JsonValue list : rest) {
            if (!list.isArray())
                throw new BindingTreeException("a scope's lists must be arrays: " + shortText(list));
            lists.add(iterationsFromJson(list.getAsArray()));
        }
        return new BindingTree(own, lists);
    }

    private static List<BindingTree> iterationsFromJson(JsonArray items) {
        List<BindingTree> out = new ArrayList<>();
        for (JsonValue e : items)
            out.add(iterationFromJson(e));
        return out;
    }

    private static BindingTree iterationFromJson(JsonValue elt) {
        if (elt.isObject())
            return scopeFromJson(elt);
        if (elt.isArray()) {
            JsonArray a = elt.getAsArray();
            if (isScopeArray(a))            // also L2: a nested scope as a sibling
                return scopeFromJson(elt);
            if (allMatch(a, JsonValue::isObject))   // shex.js, a nested scope with no own bindings, one list unwrapped
                return new BindingTree(Map.of(), List.of(iterationsFromJson(a)));
        }
        throw new BindingTreeException("not an iteration: " + shortText(elt));
    }

    private static String shortText(JsonValue v) {
        String s = JSON.toStringFlat(v);
        return s.length() < 120 ? s : s.substring(0, 117) + "...";
    }

    /** A canonical text of the tree (JSON with sorted keys), for comparisons. */
    public String canonical() {
        StringBuilder sb = new StringBuilder();
        canonicalInto(toJson(), sb);
        return sb.toString();
    }

    static void canonicalInto(JsonValue v, StringBuilder sb) {
        if (v.isObject()) {
            JsonObject o = v.getAsObject();
            sb.append('{');
            boolean first = true;
            for (String k : new TreeMap<>(o.keys().stream().collect(java.util.stream.Collectors.toMap(k -> k, k -> k))).keySet()) {
                if (!first)
                    sb.append(',');
                first = false;
                sb.append(JSON.toStringFlat(new org.apache.jena.atlas.json.JsonString(k))).append(':');
                canonicalInto(o.get(k), sb);
            }
            sb.append('}');
        } else if (v.isArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonValue e : v.getAsArray()) {
                if (!first)
                    sb.append(',');
                first = false;
                canonicalInto(e, sb);
            }
            sb.append(']');
        } else {
            sb.append(JSON.toStringFlat(v));
        }
    }

    // -- reading --------------------------------------------------------------------------------

    /** Every variable bound anywhere in the tree. */
    public Set<String> variables() {
        Set<String> out = new LinkedHashSet<>();
        collectVariables(out);
        return out;
    }

    private void collectVariables(Set<String> out) {
        for (String k : own.keySet())
            if (!k.startsWith("@"))
                out.add(k);
        for (List<BindingTree> list : lists)
            for (BindingTree it : list)
                it.collectVariables(out);
    }

    /** Every scope of the tree that recorded {@code @node}. */
    public List<BindingTree> nodes() {
        List<BindingTree> out = new ArrayList<>();
        collectNodes(out);
        return out;
    }

    private void collectNodes(List<BindingTree> out) {
        if (own.containsKey(NODE_KEY))
            out.add(this);
        for (List<BindingTree> list : lists)
            for (BindingTree it : list)
                it.collectNodes(out);
    }

    /** The tree flattened to the frame sequence, as shex.js's {@code normalizeBindingTree} does: a
     * binding whose variable occurs once under a list level (a patient's name beside the list of
     * their readings) is copied into every frame the sibling lists produce; {@code @node} is left
     * out. */
    public List<Map<String, Node>> frames() {
        return walkScope(this).frames;
    }

    private static final class Walked {
        final List<Map<String, Node>> frames;
        final boolean leaf;
        final Map<String, Integer> counts;
        Walked(List<Map<String, Node>> frames, boolean leaf, Map<String, Integer> counts) {
            this.frames = frames; this.leaf = leaf; this.counts = counts;
        }
    }

    private static Walked walkScope(BindingTree scope) {
        Map<String, Node> ownFrame = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, Node> e : scope.own.entrySet())
            if (!e.getKey().equals(NODE_KEY)) {
                ownFrame.put(e.getKey(), e.getValue());
                counts.put(e.getKey(), 1);
            }
        List<Map<String, Node>> one = new ArrayList<>();
        one.add(ownFrame);
        Walked leaf = new Walked(one, true, counts);
        if (scope.lists.isEmpty())
            return leaf;
        List<Walked> kids = new ArrayList<>();
        kids.add(leaf);
        for (List<BindingTree> list : scope.lists) {
            List<Walked> items = new ArrayList<>();
            for (BindingTree it : list)
                items.add(walkScope(it));
            kids.add(combine(items));
        }
        return combine(kids);
    }

    private static Walked combine(List<Walked> kids) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Walked k : kids)
            for (Map.Entry<String, Integer> e : k.counts.entrySet())
                counts.merge(e.getKey(), e.getValue(), Integer::sum);
        if (kids.stream().allMatch(k -> k.leaf)) {     // a plain sequence of frames
            List<Map<String, Node>> out = new ArrayList<>();
            for (Walked k : kids)
                out.addAll(k.frames);
            return new Walked(out, false, counts);
        }
        Map<String, Node> shared = new LinkedHashMap<>();
        List<Object[]> ordered = new ArrayList<>();   // (frames, leaf)
        for (Walked k : kids) {
            if (k.leaf) {
                Map<String, Node> rest = new LinkedHashMap<>();
                if (!k.frames.isEmpty())
                    for (Map.Entry<String, Node> e : k.frames.get(0).entrySet()) {
                        if (counts.get(e.getKey()) == 1)
                            shared.put(e.getKey(), e.getValue());
                        else
                            rest.put(e.getKey(), e.getValue());
                    }
                if (!rest.isEmpty()) {
                    List<Map<String, Node>> restFrames = new ArrayList<>();
                    restFrames.add(rest);
                    ordered.add(new Object[] { restFrames, Boolean.TRUE });
                }
            } else {
                ordered.add(new Object[] { k.frames, Boolean.FALSE });
            }
        }
        List<Map<String, Node>> out = new ArrayList<>();
        for (Object[] o : ordered) {
            @SuppressWarnings("unchecked")
            List<Map<String, Node>> frames = (List<Map<String, Node>>) o[0];
            boolean isLeaf = (Boolean) o[1];
            for (Map<String, Node> f : frames) {
                if (isLeaf) {
                    out.add(f);
                } else {
                    Map<String, Node> merged = new LinkedHashMap<>(shared);
                    merged.putAll(f);
                    out.add(merged);
                }
            }
        }
        if (out.isEmpty() && !shared.isEmpty())    // a scope whose lists are all empty still has its own bindings
            out.add(shared);
        return new Walked(out, false, counts);
    }

    @Override public boolean equals(Object o) {
        return o instanceof BindingTree t && canonical().equals(t.canonical());
    }

    @Override public int hashCode() { return canonical().hashCode(); }

    @Override public String toString() { return JSON.toStringFlat(toJson()); }
}
