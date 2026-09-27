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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shex.Shex;
import org.apache.jena.shex.ShexSchema;
import org.apache.jena.shex.ShexValidator;
import org.apache.jena.shex.expressions.SemAct;
import org.junit.Test;

public class TestMapSemanticActionPlugin {
    private static final String SCHEMA = "PREFIX : <http://e/>\nPREFIX bp: <http://v/>\nPREFIX Map: <http://shex.io/extensions/Map/#>\n"
            + "start = @<S>\n<S> { :name . %Map:{ regex(/(?<bp:family>[a-zA-Z]+), (?<bp:given>[a-zA-Z]+)/) %} ; :given . %Map:{ bp:g %} }\n";

    private static Graph graph(String ttl) {
        return RDFParser.create().fromString(ttl).lang(Lang.TURTLE).base("http://e/").toGraph();
    }

    @Test public void regexMustMatchDuringValidation() {
        ShexSchema schema = Shex.schemaFromString(SCHEMA, "http://e/");
        ShexValidator v = ShexValidator.getNew(List.of(new MapSemanticActionPlugin()));
        Node s = NodeFactory.createURI("http://e/s1");
        assertTrue(v.validate(graph("PREFIX : <http://e/>\n<s1> :name \"Walker, Alice\" ; :given \"Alice\" ."), schema, schema.getStart(), s).conforms());
        assertFalse(v.validate(graph("PREFIX : <http://e/>\n<s1> :name \"nope\" ; :given \"Alice\" ."), schema, schema.getStart(), s).conforms());
        // without the plugin, Map codes are ignored and both conform
        assertTrue(ShexValidator.getNew(List.of()).validate(graph("PREFIX : <http://e/>\n<s1> :name \"nope\" ; :given \"Alice\" ."), schema, schema.getStart(), s).conforms());
    }

    @Test public void variablesAndIdAreAccepted() {
        MapSemanticActionPlugin p = new MapSemanticActionPlugin();
        Triple t = Triple.create(NodeFactory.createURI("http://e/s"), NodeFactory.createURI("http://e/p"), NodeFactory.createLiteralString("x"));
        assertTrue(p.evaluateTripleExpr(new SemAct(ShExMap.MAP_EXTENSION, " bp:given "), null, List.of(t)));
        assertTrue(p.evaluateTripleExpr(new SemAct(ShExMap.MAP_EXTENSION, "<http://v/x>"), null, List.of(t)));
        assertTrue(p.evaluateTripleExpr(new SemAct(ShExMap.MAP_EXTENSION, "id(bp:a, @node)"), null, List.of(t)));
        assertThrows(MapFunctionException.class, () -> p.evaluateTripleExpr(new SemAct(ShExMap.MAP_EXTENSION, "id()"), null, List.of(t)));
        assertThrows(MapFunctionException.class, () -> p.evaluateTripleExpr(new SemAct(ShExMap.MAP_EXTENSION, "bogus(x)"), null, List.of(t)));
    }
}
