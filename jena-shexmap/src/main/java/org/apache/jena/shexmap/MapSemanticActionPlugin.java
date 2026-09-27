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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.shex.ShexSchema;
import org.apache.jena.shex.expressions.SemAct;
import org.apache.jena.shex.expressions.ShapeExpr;
import org.apache.jena.shex.expressions.TripleExpr;
import org.apache.jena.shex.semact.SemanticActionPlugin;
import org.apache.jena.shexmap.SchemaIndex.Tc;

/** The Map extension ({@code http://shex.io/extensions/Map/#}) as a Jena semantic-action plugin.
 * <p>
 * A {@code %Map:{ ... %}} code on a triple constraint is a variable name ({@code <iri>} or
 * {@code prefix:local}) or a function call ({@code regex(...)}, {@code hashmap(...)},
 * {@code id(...)}; see {@link MapFunctions}).  During validation a variable binds whatever the
 * constraint matched and cannot fail; {@code regex(...)} and {@code hashmap(...)} must apply to the
 * matched value, else the triple constraint fails, as in shex.js; {@code id(...)} names an output
 * node and is accepted.  A malformed code is a schema error and is thrown.  Bindings are collected
 * after validation by {@link Binder}, with their nesting; this plugin records nothing. */
public final class MapSemanticActionPlugin implements SemanticActionPlugin {
    public static final String IRI = SchemaIndex.MAP_EXTENSION;
    public static final String IRI_NO_HASH = IRI.substring(0, IRI.length() - 1);

    /** The plugin under both spellings of its IRI, for a validation context. */
    public static Map<String, SemanticActionPlugin> index() {
        MapSemanticActionPlugin p = new MapSemanticActionPlugin();
        Map<String, SemanticActionPlugin> out = new HashMap<>();
        for (String u : p.getUris())
            out.put(u, p);
        return out;
    }

    @Override
    public List<String> getUris() {
        return List.of(IRI, IRI_NO_HASH);
    }

    @Override
    public boolean evaluateStart(SemAct semAct, ShexSchema schema) {
        return true;
    }

    @Override
    public boolean evaluateShapeExpr(SemAct semAct, ShapeExpr shapeExpr, Node focus) {
        return true;
    }

    @Override
    public boolean evaluateTripleExpr(SemAct semAct, TripleExpr tripleExpression, Collection<Triple> triples) {
        String code = SchemaIndex.codeOf(semAct).strip();
        Map<String, String> prefixes = MapFunctions.anyPrefixes(code);
        if (!MapFunctions.isFunctionCall(code)) {
            MapFunctions.expandVariable(code, prefixes);    // the form is checked; a variable cannot fail
            return true;
        }
        MapFunctions.variablesOf(code, prefixes);           // a malformed code throws here
        if (MapFunctions.isKeyCode(code))
            return true;
        Tc tc = Tc.of(tripleExpression);
        boolean inverse = tc != null && tc.inverse();
        for (Triple t : triples) {
            Node value = inverse ? t.getSubject() : t.getObject();
            try {
                MapFunctions.lift(code, value, prefixes);
            } catch (MapFunctionException e) {
                return false;                               // no match, no entry: the constraint fails
            }
        }
        return true;
    }

    /** The bindings a code makes from a value, for callers outside validation. */
    public static Map<String, Node> lift(String code, Node value, Map<String, String> prefixes) {
        Map<String, Node> out = new LinkedHashMap<>();
        if (MapFunctions.isFunctionCall(code))
            out.putAll(MapFunctions.lift(code, value, prefixes));
        else
            out.put(MapFunctions.expandVariable(code, prefixes), value);
        return out;
    }

    static List<String> uris() {
        return new ArrayList<>(List.of(IRI, IRI_NO_HASH));
    }
}
