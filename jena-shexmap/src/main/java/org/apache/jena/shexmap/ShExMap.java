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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.shex.ShexSchema;
import org.apache.jena.shex.semact.SemanticActionPlugin;

/** ShExMap: map RDF from one ShEx schema to another.
 * <p>
 * A port of the design shared by shex.js's {@code @shexjs/extension-map} and PyShEx's
 * {@code pyshex.shexmap}.  Semantic actions in the {@code http://shex.io/extensions/Map/#}
 * extension annotate triple constraints:
 * <ul>
 * <li>in the <b>input</b> schema, {@code %Map:{ bp:given %}} binds the variable {@code bp:given} to
 *     the value the triple constraint matched, and {@code %Map:{ regex(/(?<bp:family>...)/) %}} or
 *     {@code %Map:{ hashmap(var, {...}) %}} bind variables computed from it;</li>
 * <li>in the <b>output</b> schema, the same annotations say where each bound value goes, and
 *     {@code %Map:{ id(var, ...) %}} on a shape-valued constraint names the node it builds.</li>
 * </ul>
 * <pre>
 * Bindings bindings = ShExMap.bind(graph, inputSchema, focus, null, false);
 * Graph out = ShExMap.materialize(outputSchema, bindings, root, null, null);
 * </pre>
 * or both at once with {@link #mapGraph}.  The {@code shex map} command does the same from a
 * terminal.
 * <ul>
 * <li>{@link #bind} validates the focus with Jena's ShEx validator, then partitions each
 *     neighbourhood by exhaustive search; when the input conforms in several ways that bind
 *     differently it says so ({@link Bindings#alternatives()}), and {@link #bindAll} returns every
 *     way.  EXTENDS and inverse ({@code ^p}) constraints are followed.</li>
 * <li>{@link #analyse} checks a schema pair before any data: every output repetition has a list to
 *     iterate, every variable read is bound where it can be read from, and unused bindings are
 *     reported.</li>
 * <li>{@link Materializer} builds the output from the binding tree's structure: an output
 *     repetition iterates the input list its variables are bound at, a parent's binding is read in
 *     every item, and the alternatives a {@code OneOf} or an extension leaves open are kept, the one
 *     that reads the most bindings returned.  It can also update a graph in place.</li>
 * </ul>
 * The binding tree's JSON is shex.js's and PyShEx's (see {@link BindingTree}). */
public final class ShExMap {
    private ShExMap() {}

    /** The Map extension's IRI. */
    public static final String MAP_EXTENSION = SchemaIndex.MAP_EXTENSION;

    /** The Map extension as a semantic-action plugin, under both spellings of its IRI, for
     * validators that should check {@code regex()} and {@code hashmap()} codes. */
    public static Map<String, SemanticActionPlugin> plugins() {
        return MapSemanticActionPlugin.index();
    }

    /** Validate {@code focus} against {@code schema} and collect its ShExMap bindings.
     *
     * @param start the shape label to match against; the schema's start when null
     * @param strict fail when the input conforms in several ways that bind differently
     * @throws MapValidationException when {@code focus} does not conform */
    public static Bindings bind(Graph graph, ShexSchema schema, Node focus, Node start, boolean strict) {
        return Binder.bind(graph, schema, focus, start, strict, null);
    }

    /** Every distinct binding tree the input allows for {@code focus}, up to {@code limit}. */
    public static List<Bindings> bindAll(Graph graph, ShexSchema schema, Node focus, Node start, int limit) {
        return Binder.bindAll(graph, schema, focus, start, limit, null);
    }

    /** Materialize {@code root} (a new blank node when null) as an instance of {@code start} in the
     * output schema (its start when null) from {@code bindings}, into a new graph. */
    public static Graph materialize(ShexSchema schema, Bindings bindings, Node root, Node start, Materializer.Options options) {
        return Materializer.materialize(schema, bindings, root, start, options);
    }

    /** Bind {@code focus} against {@code inputSchema} and materialize {@code root} in {@code outputSchema}. */
    public static Graph mapGraph(Graph graph, ShexSchema inputSchema, Node focus, Node inputStart,
                                 ShexSchema outputSchema, Node root, Node outputStart,
                                 Materializer.Options options, boolean strict) {
        Bindings bindings = bind(graph, inputSchema, focus, inputStart, strict);
        return materialize(outputSchema, bindings, root, outputStart, options);
    }

    /** Check that {@code output} can be materialized coherently from what {@code input} binds. */
    public static Analysis.Report analyse(ShexSchema input, ShexSchema output, Node inputStart, Node outputStart, Set<String> staticVars) {
        return Analysis.analyse(input, output, inputStart, outputStart, staticVars);
    }
}
