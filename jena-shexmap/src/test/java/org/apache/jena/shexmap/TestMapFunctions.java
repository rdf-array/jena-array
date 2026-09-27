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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.junit.Test;

public class TestMapFunctions {
    private static final Map<String, String> PREFIXES = Map.of("bp", "http://shex.io/extensions/Map/#BPDAM-");

    private static Node lit(String s) { return NodeFactory.createLiteralString(s); }

    @Test public void variablesExpand() {
        assertEquals("http://shex.io/extensions/Map/#BPDAM-given", MapFunctions.expandVariable(" bp:given ", PREFIXES));
        assertEquals("http://x/y", MapFunctions.expandVariable("<http://x/y>", PREFIXES));
        assertThrows(MapFunctionException.class, () -> MapFunctions.expandVariable("zz:a", PREFIXES));
        assertThrows(MapFunctionException.class, () -> MapFunctions.expandVariable("regex(/a/)", PREFIXES));
    }

    @Test public void regexLiftsAndLowers() {
        String code = "regex(/(?<bp:family>[a-zA-Z]+), (?<bp:given>[a-zA-Z]+)/)";
        assertTrue(MapFunctions.isFunctionCall(code));
        Map<String, Node> bound = MapFunctions.lift(code, lit("Walker, Alice"), PREFIXES);
        assertEquals(lit("Walker"), bound.get("http://shex.io/extensions/Map/#BPDAM-family"));
        assertEquals(lit("Alice"), bound.get("http://shex.io/extensions/Map/#BPDAM-given"));
        assertEquals(lit("Walker, Alice"), MapFunctions.lower(code, bound::get, PREFIXES));
        assertThrows(MapFunctionException.class, () -> MapFunctions.lift(code, lit("nope"), PREFIXES));
        assertEquals(List.of("http://shex.io/extensions/Map/#BPDAM-family", "http://shex.io/extensions/Map/#BPDAM-given"),
                MapFunctions.variablesOf(code, PREFIXES));
        assertNull(MapFunctions.lower(code, v -> null, PREFIXES));
    }

    @Test public void hashmapLiftsAndLowers() {
        String code = "hashmap(bp:status, {\"D\": \"Divorced\", \"M\": \"Married\"})";
        Map<String, Node> bound = MapFunctions.lift(code, lit("M"), PREFIXES);
        assertEquals(lit("Married"), bound.get("http://shex.io/extensions/Map/#BPDAM-status"));
        assertEquals(lit("M"), MapFunctions.lower(code, bound::get, PREFIXES));
        assertThrows(MapFunctionException.class, () -> MapFunctions.lift(code, lit("X"), PREFIXES));
        assertThrows(MapFunctionException.class,
                () -> MapFunctions.lift("hashmap(bp:status, {\"D\": \"Same\", \"M\": \"Same\"})", lit("D"), PREFIXES));
    }

    @Test public void idTerms() {
        assertTrue(MapFunctions.isKeyCode(" id(bp:a, @node) "));
        assertFalse(MapFunctions.isKeyCode("bp:a"));
        List<MapFunctions.KeyTerm> terms = MapFunctions.keyTerms("id(bp:a, @node, <http://a.example/p/{bp:b}/x>)", PREFIXES);
        assertEquals(3, terms.size());
        assertTrue(terms.get(1) instanceof MapFunctions.NodeArgument);
        assertEquals(List.of("http://shex.io/extensions/Map/#BPDAM-a", "@node", "http://shex.io/extensions/Map/#BPDAM-b"),
                MapFunctions.keyArguments("id(bp:a, @node, <http://a.example/p/{bp:b}/x>)", PREFIXES));
        assertThrows(MapFunctionException.class, () -> MapFunctions.keyTerms("id()", PREFIXES));
        assertTrue(MapFunctions.lift("id(bp:a)", lit("x"), PREFIXES).isEmpty());
    }

    @Test public void templatesPercentEncode() {
        MapFunctions.Template t = new MapFunctions.Template("http://a.example/person/{bp:mrn}", PREFIXES);
        assertEquals(NodeFactory.createURI("http://a.example/person/a%20b%2Fc"), t.expand(v -> lit("a b/c")));
        assertNull(t.expand(v -> null));
        assertEquals("A-z.9_~", MapFunctions.percentEncode("A-z.9_~"));
    }
}
