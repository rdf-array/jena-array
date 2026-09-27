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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;

/** A binding tree, how many distinct ones the input allowed, and the triples the schema matched
 * to make it ({@link #matched}: the subgraph it selected). */
public final class Bindings {
    private final BindingTree tree;
    private final int alternatives;
    private final Set<Triple> matched;

    public Bindings(BindingTree tree) {
        this(tree, 1, Set.of());
    }

    public Bindings(BindingTree tree, int alternatives, Set<Triple> matched) {
        this.tree = tree;
        this.alternatives = alternatives;
        this.matched = new LinkedHashSet<>(matched);
    }

    public BindingTree tree() { return tree; }

    /** How many distinct binding trees the input allowed. */
    public int alternatives() { return alternatives; }

    public boolean isAmbiguous() { return alternatives > 1; }

    /** The triples the input schema matched to make these bindings. */
    public Set<Triple> matched() { return matched; }

    public JsonValue toJson() { return tree.toJson(); }

    public static Bindings fromJson(JsonValue v) { return new Bindings(BindingTree.fromJson(v)); }

    /** The tree flattened to frames (see {@link BindingTree#frames}). */
    public List<Map<String, Node>> frames() { return tree.frames(); }

    public Set<String> variables() { return tree.variables(); }

    /** The bindings as JSON text, shex.js's format. */
    public String dumps(boolean pretty) {
        return pretty ? JSON.toString(toJson()) : JSON.toStringFlat(toJson());
    }

    public static Bindings loads(String text) {
        try {
            return fromJson(JSON.parseAny(text));
        } catch (org.apache.jena.atlas.json.JsonException e) {
            throw new BindingTreeException("bindings JSON: " + e.getMessage());
        }
    }

    @Override public boolean equals(Object o) {
        return o instanceof Bindings b && tree.equals(b.tree);
    }

    @Override public int hashCode() { return tree.hashCode(); }

    @Override public String toString() {
        String s = dumps(false);
        return "Bindings(" + (s.length() > 200 ? s.substring(0, 200) : s) + ", alternatives=" + alternatives + ")";
    }
}
