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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jena.graph.Node;

/** The scope tree: a binding tree read structurally, for materialization by iteration scopes.
 * <p>
 * Scopes live in an arena and are addressed by index (walk order); each knows its parent, so
 * a variable is looked up along the ancestor chain.  A <em>list path</em> names a list by
 * position: the list indices from the root, so {@code []} is the root scope itself, {@code [0]}
 * the root's first list, {@code [0, 1]} the second list of an iteration of that list.  Every
 * iteration of a list shares the list's path; a variable bound in those iterations is
 * <em>bound at</em> that list path. */
public final class ScopeTree {
    /** One scope of the tree. */
    public static final class Scope {
        /** Variables bound here (never {@code @node}). */
        public final Map<String, Node> own = new LinkedHashMap<>();
        /** The node this iteration matched, if recorded. */
        public final Node node;
        /** One list of iteration scopes (indices) per repeated expression. */
        public final List<List<Integer>> lists = new ArrayList<>();
        public final int parent;                 // -1 for the root
        /** The list this scope is an iteration of. */
        public final List<Integer> listPath;
        /** (list index, iteration index, ...) from the root. */
        public final List<Integer> path;

        Scope(Node node, int parent, List<Integer> listPath, List<Integer> path) {
            this.node = node;
            this.parent = parent;
            this.listPath = List.copyOf(listPath);
            this.path = List.copyOf(path);
        }

        public int depth() { return listPath.size(); }
    }

    private final List<Scope> scopes = new ArrayList<>();
    /** Variable to the list path it is bound at. */
    private final Map<String, List<Integer>> boundAt = new HashMap<>();
    private int bindings = 0;

    public ScopeTree(BindingTree tree) {
        add(tree, -1, List.of(), List.of());
        for (Scope s : scopes)
            for (String v : s.own.keySet()) {
                bindings++;
                List<Integer> at = boundAt.putIfAbsent(v, s.listPath);
                if (at != null && !at.equals(s.listPath))
                    throw new BindingTreeException("variable " + v + " is bound at two lists, " + at + " and " + s.listPath);
            }
    }

    private int add(BindingTree tree, int parent, List<Integer> listPath, List<Integer> path) {
        int id = scopes.size();
        Scope scope = new Scope(tree.node(), parent, listPath, path);
        for (Map.Entry<String, Node> e : tree.own().entrySet())
            if (!e.getKey().startsWith("@"))
                scope.own.put(e.getKey(), e.getValue());
        scopes.add(scope);
        int i = 0;
        for (List<BindingTree> list : tree.lists()) {
            List<Integer> lp = new ArrayList<>(listPath);
            lp.add(i);
            List<Integer> ids = new ArrayList<>();
            int j = 0;
            for (BindingTree it : list) {
                List<Integer> p = new ArrayList<>(path);
                p.add(i);
                p.add(j);
                ids.add(add(it, id, lp, p));
                j++;
            }
            scope.lists.add(ids);
            i++;
        }
        return id;
    }

    public int root() { return 0; }

    public Scope scope(int id) { return scopes.get(id); }

    public int size() { return scopes.size(); }

    /** How many bindings the tree holds. */
    public int bindings() { return bindings; }

    /** Variable to the list path it is bound at. */
    public Map<String, List<Integer>> boundAt() { return boundAt; }

    public Set<String> variables() { return boundAt.keySet(); }

    /** The value of {@code var} at {@code id} or the nearest ancestor binding it, and that scope's
     * index; null when unbound on the chain. */
    public Map.Entry<Node, Integer> lookup(int id, String var) {
        for (int cur = id; cur >= 0; cur = scopes.get(cur).parent) {
            Node v = scopes.get(cur).own.get(var);
            if (v != null)
                return Map.entry(v, cur);
        }
        return null;
    }

    /** The node of this scope or of the nearest ancestor that recorded one. */
    public Node nearestNode(int id) {
        for (int cur = id; cur >= 0; cur = scopes.get(cur).parent) {
            Node n = scopes.get(cur).node;
            if (n != null)
                return n;
        }
        return null;
    }

    static boolean isPrefix(List<Integer> a, List<Integer> b) {
        return b.size() >= a.size() && b.subList(0, a.size()).equals(a);
    }

    /** The scopes below {@code id} that are iterations of {@code listPath}, in document order. */
    public List<Integer> descendantsAt(int id, List<Integer> listPath) {
        Scope here = scopes.get(id);
        List<Integer> out = new ArrayList<>();
        if (!isPrefix(here.listPath, listPath) || listPath.equals(here.listPath))
            return out;
        for (List<Integer> list : here.lists)
            for (int it : list) {
                List<Integer> lp = scopes.get(it).listPath;
                if (lp.equals(listPath))
                    out.add(it);
                else if (isPrefix(lp, listPath))
                    out.addAll(descendantsAt(it, listPath));
            }
        return out;
    }
}
