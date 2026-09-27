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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.Node;
import org.junit.Test;

public class TestBindingTree {
    @Test public void treesRoundTripAndFlatten() {
        JsonValue j = JSON.parseAny("[{\"http://v/name\": {\"value\": \"Sue\"}},"
                + " [{\"@node\": \"tag:b0\", \"http://v/sys\": {\"value\": \"110\"}},"
                + "  {\"@node\": \"tag:b1\", \"http://v/sys\": {\"value\": \"111\"}}]]");
        BindingTree tree = BindingTree.fromJson(j);
        assertEquals(j, tree.toJson());
        List<Map<String, Node>> frames = tree.frames();
        assertEquals(2, frames.size());
        assertTrue(frames.stream().allMatch(f -> f.containsKey("http://v/name") && !f.containsKey("@node")));
        assertEquals(2, tree.nodes().size());
        assertEquals(2, tree.variables().size());
        assertEquals(tree, Bindings.loads(new Bindings(tree).dumps(true)).tree());
    }

    @Test public void legacyLayoutsAreRead() {
        BindingTree t = BindingTree.fromJson(JSON.parseAny("[{\"http://v/a\": \"http://x\"}, {\"http://v/a\": \"http://y\"}]"));
        assertEquals(1, t.lists().size());
        assertEquals(2, t.lists().get(0).size());
        BindingTree mixed = BindingTree.fromJson(JSON.parseAny("[{\"http://v/n\": {\"value\": \"x\"}},"
                + " [[{\"http://v/a\": \"http://x\"}, [{\"http://v/b\": \"http://y\"}]], {\"http://v/a\": \"http://z\"}]]"));
        assertEquals(1, mixed.lists().get(0).get(0).lists().size());
        assertTrue(mixed.lists().get(0).get(1).lists().isEmpty());
        // written back uniformly: the lone object is wrapped
        assertEquals(JSON.parseAny("[{\"http://v/a\": \"http://z\"}]"), mixed.toJson().getAsArray().get(1).getAsArray().get(1));
    }

    @Test public void aScopeWithEmptyListsKeepsItsFrame() {
        BindingTree t = BindingTree.fromJson(JSON.parseAny("[{\"http://v/n\": {\"value\": \"x\"}}, []]"));
        assertEquals(1, t.frames().size());
        assertFalse(t.frames().get(0).isEmpty());
    }

    @Test public void termsRoundTrip() {
        for (String n3 : List.of("<http://a.example/x>", "_:b0", "\"Sue\"", "\"110\"^^<http://www.w3.org/2001/XMLSchema#float>", "\"bonjour\"@fr")) {
            Node n = Terms.parseTerm(n3);
            assertEquals(n, Terms.fromJson(Terms.toJson(n)));
            assertEquals(n3, Terms.n3(n));
        }
        assertEquals(JSON.parseAny("{\"value\": \"110\", \"type\": \"http://www.w3.org/2001/XMLSchema#float\"}"),
                Terms.toJson(Terms.parseTerm("\"110\"^^<http://www.w3.org/2001/XMLSchema#float>")));
        assertEquals(16, Terms.digest("a", "b").length());
    }
}
