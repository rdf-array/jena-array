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

package org.apache.jena.shex.parser;

import java.io.InputStream;

import org.apache.jena.atlas.lib.IRILib;
import org.apache.jena.graph.Graph;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shex.ShexSchema;

/** Shape Expressions : RDF syntax */
public class ShExR {

    /** Parse an already-loaded RDF graph, conforming to ShExR, into a {@link ShexSchema}. */
    public static ShexSchema parse(Graph graph, String sourceURI, String baseURI) {
        ShexSchema schema = ParserShExR.parse(graph, sourceURI, baseURI);
        // Same facet-ordering / numeric-datatype checks ShExC applies after parsing.
        ShExC.validatePhase2(schema);
        return schema;
    }

    /** Read the file or URL (any RDF syntax {@link RDFDataMgr} recognizes) as a ShExR schema. */
    public static ShexSchema parse(String filenameOrURL) {
        return parse(filenameOrURL, IRILib.filenameToIRI(filenameOrURL));
    }

    /** Read the file or URL (any RDF syntax {@link RDFDataMgr} recognizes) as a ShExR schema. */
    public static ShexSchema parse(String filenameOrURL, String baseURI) {
        Graph graph = RDFDataMgr.loadGraph(filenameOrURL);
        return parse(graph, IRILib.filenameToIRI(filenameOrURL), baseURI);
    }

    /** Parse an {@code InputStream} in the given RDF syntax (default Turtle) as a ShExR schema. */
    public static ShexSchema parse(InputStream input, String originURI, String baseURI, Lang lang) {
        Graph graph = RDFParser.create()
                .source(input)
                .lang(lang == null ? Lang.TURTLE : lang)
                .base(baseURI)
                .toGraph();
        return parse(graph, originURI, baseURI);
    }
}
