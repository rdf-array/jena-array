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

package org.apache.jena.sparql.engine.main.solver;

import static org.apache.jena.sparql.engine.main.solver.SolverLib.nodeTopLevel;
import static org.apache.jena.sparql.engine.main.solver.SolverLib.tripleHasEmbTripleWithVars;

import java.util.Iterator;
import java.util.function.Predicate;

import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Substitute;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.binding.Binding;

/**
 * Compound terms - quad form. Solve patterns when there is a triple term
 * (RDF-star) or a sequence with variables inside, or a sequence position pattern
 * {@code ((element))[index]}.
 */
public class SolverRX4 {

    /**
     * This constant is not public API. It is exposed only so integration testing can
     * check the value for a release build.
     */
    public static final boolean DATAPATH = true;

    public static Iterator<Binding> rdfStarQuad(Iterator<Binding> chain, Quad pattern, ExecutionContext execCxt) {
        return rdfStarQuad(chain, pattern.getGraph(), pattern.asTriple(), execCxt) ;
    }

    public static Iterator<Binding> rdfStarQuad(Iterator<Binding> chain, Node graphName, Triple pattern, ExecutionContext execCxt) {
        // Should all work without this trap for plain RDF.
        if ( DATAPATH ) {
            if ( ! tripleHasEmbTripleWithVars(pattern) &&
                    (graphName == null || ! graphName.isNodeTriple() ) )
                // No variables inside <<>> or (..), no ((..))[..]
                return matchDataQuad(chain, graphName, pattern, execCxt);
        }
        return rdfStarQuadSub(chain, graphName, pattern, execCxt);
    }

    private static Iterator<Binding> rdfStarQuadSub(Iterator<Binding> chain, Node graphName, Triple pattern, ExecutionContext execCxt) {
        Iterator<Binding> matches = Iter.flatMap(chain, binding->rdfStarQuadSub(binding, graphName, pattern, execCxt));
        return matches;
    }

    private static Iterator<Binding> rdfStarQuadSub(Binding binding, Node xGraphName, Triple xPattern, ExecutionContext execCxt) {
        Triple tPattern = Substitute.substitute(xPattern, binding);
        Node graphName = Substitute.substitute(xGraphName, binding);
        Node gPattern = (graphName == null) ? Quad.defaultGraphIRI : graphName;
        Node g = nodeTopLevel(gPattern);
        Node s = nodeTopLevel(tPattern.getSubject());
        Node p = nodeTopLevel(tPattern.getPredicate());
        Node o = nodeTopLevel(tPattern.getObject());
        DatasetGraph dsg = execCxt.getDataset();
        Iterator<Quad> dataIter = dsg.find(g, s, p, o);
        return Iter.flatMap(dataIter, qData->matchQuad(binding, qData, gPattern, tPattern));
    }

    private static final Predicate<Quad> NoFilter = null;
    private static final boolean NoAnyGraph = false;

    private static Iterator<Binding> matchDataQuad(Iterator<Binding> chain, Node graphName, Triple pattern, ExecutionContext execCxt) {
        Iterator<Binding> matches = StageMatchData.accessQuad(chain, graphName, pattern, NoFilter, NoAnyGraph, execCxt);
        return matches;
    }

    /**
     * Match data "qData" against "tGraphNode, tPattern", including compound terms.
     * There may be several results: see {@link SolverRX3#matchTriple}.
     */
    public static Iterator<Binding> matchQuad(Binding input, Quad qData, Node tGraphNode, Triple tPattern) {
        Iterator<Binding> iter = Iter.singleton(input);
        iter = Iter.flatMap(iter, b->SolverRX3.match(b, qData.getGraph(), tGraphNode));
        iter = Iter.flatMap(iter, b->SolverRX3.match(b, qData.getSubject(), tPattern.getSubject()));
        iter = Iter.flatMap(iter, b->SolverRX3.match(b, qData.getPredicate(), tPattern.getPredicate()));
        iter = Iter.flatMap(iter, b->SolverRX3.match(b, qData.getObject(), tPattern.getObject()));
        return iter;
    }
}
