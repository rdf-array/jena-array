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
import static org.apache.jena.sparql.engine.main.solver.SolverLib.sameTermAs;
import static org.apache.jena.sparql.engine.main.solver.SolverLib.tripleHasEmbTripleWithVars;

import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;

import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.datatypes.DatatypeFormatException;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.Node_SeqIndex;
import org.apache.jena.sparql.core.Substitute;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.sparql.util.NodeFactoryExtra;
import org.apache.jena.util.iterator.ExtendedIterator;

/**
 * Compound terms - triple form. Solve patterns when there is a triple term
 * (RDF-star) or a sequence with variables inside, or a sequence position pattern
 * {@code ((element))[index]}.
 */
public class SolverRX3 {

    /**
     * This constant is not public API. It is exposed only so integration testing can
     * check the value for a release build.
     */
    public static final boolean DATAPATH = true;

    public static Iterator<Binding> rdfStarTriple(Iterator<Binding> chain, Triple tPattern, ExecutionContext execCxt) {
        // Should all work without this trap for plain RDF.
        if ( DATAPATH ) {
            // No variables inside <<>> or (..), no ((..))[..]
            if ( ! tripleHasEmbTripleWithVars(tPattern) )
                // Direct to data.
                return matchData(chain, tPattern, execCxt);
        }
        return rdfStarTripleSub(chain, tPattern, execCxt);
    }

    private static Iterator<Binding> rdfStarTripleSub(Iterator<Binding> input, Triple tPattern, ExecutionContext execCxt) {
        Iterator<Binding> matches = Iter.flatMap(input, binding->rdfStarTripleSub(binding, tPattern, execCxt));
        return matches;
    }

    private static Iterator<Binding> rdfStarTripleSub(Binding input, Triple xPattern, ExecutionContext execCxt) {
        Triple tPattern = Substitute.substitute(xPattern, input);
        Node s = nodeTopLevel(tPattern.getSubject());
        Node p = nodeTopLevel(tPattern.getPredicate());
        Node o = nodeTopLevel(tPattern.getObject());
        Graph graph = execCxt.getActiveGraph();
        ExtendedIterator<Triple> graphIter = graph.find(s, p, o);
        return Iter.flatMap(graphIter, tData->matchTriple(input, tData, tPattern));
    }

    /**
     * Direct.
     * Match the graph with a triple pattern.
     * This is the accessor to the graph.
     * It assumes any compound terms have been dealt with.
     */
    private static Iterator<Binding> matchData(Iterator<Binding> chain, Triple pattern, ExecutionContext execCxt) {
        Graph g = execCxt.getActiveGraph();
        Iterator<Binding> iter = StageMatchTriple.accessTriple(chain, g, pattern, null, execCxt);
        return iter;
    }

    /**
     * Match a data triple against a triple pattern, extending the input binding.
     * There may be several results: the sequence position pattern
     * {@code ((?x))[*]} or {@code ((?x))[?i]} yields one binding per element.
     */
    public static Iterator<Binding> matchTriple(Binding input, Triple tData, Triple tPattern) {
        Iterator<Binding> iter = Iter.singleton(input);
        iter = Iter.flatMap(iter, b->match(b, tData.getSubject(), tPattern.getSubject()));
        iter = Iter.flatMap(iter, b->match(b, tData.getPredicate(), tPattern.getPredicate()));
        iter = Iter.flatMap(iter, b->match(b, tData.getObject(), tPattern.getObject()));
        return iter;
    }

    /**
     * RDF term matcher - recurses into triple terms and sequences.
     * Returns the bindings (zero or more) that extend the input binding so that the
     * pattern matches the data.
     */
    /*package*/ static Iterator<Binding> match(Binding input, Node nData, Node nPattern) {
        if ( nPattern == null )
            return Iter.singleton(input);
        if ( nData == Node.ANY )
            return Iter.singleton(input);

        // Shallow substitute - nested variables are seen as the structure is walked.
        nPattern = Var.lookup(input::get, nPattern);

        // nPattern.isConcrete() : either nPattern is an RDF term or is <<>> / (..) with no variables.
        if ( nPattern.isConcrete() ) {
            // No nested variables. Is data equal to pattern?
            // Term comparison.
            return sameTermAs(nData, nPattern) ? Iter.singleton(input) : Iter.nullIterator();
        }

        // Easy case - nPattern is a variable.
        if ( Var.isVar(nPattern) ) {
            Var var = Var.alloc(nPattern);
            return Iter.singleton(BindingFactory.binding(input, var, nData));
        }

        // nPattern is <<>> with variables. Is the data a <<>>?
        if ( nPattern.isNodeTriple() ) {
            if ( !nData.isNodeTriple() )
                return Iter.nullIterator();
            // Unpack, match components.
            return matchTriple(input, nData.getTriple(), nPattern.getTriple());
        }

        // nPattern is (..) with variables. Is the data a (..) of the same length?
        if ( nPattern.isNodeSeq() ) {
            if ( !nData.isNodeSeq() )
                return Iter.nullIterator();
            List<Node> dElts = nData.getSeq();
            List<Node> pElts = nPattern.getSeq();
            if ( dElts.size() != pElts.size() )
                return Iter.nullIterator();
            Iterator<Binding> iter = Iter.singleton(input);
            for ( int i = 0 ; i < pElts.size() ; i++ ) {
                final int idx = i;
                iter = Iter.flatMap(iter, b->match(b, dElts.get(idx), pElts.get(idx)));
            }
            return iter;
        }

        // nPattern is ((element))[index]. Is the data a (..)?
        if ( nPattern instanceof Node_SeqIndex seqIndex )
            return matchSeqIndex(input, nData, seqIndex);

        return Iter.nullIterator();
    }

    private static Iterator<Binding> matchSeqIndex(Binding input, Node nData, Node_SeqIndex pattern) {
        if ( !nData.isNodeSeq() )
            return Iter.nullIterator();
        List<Node> dElts = nData.getSeq();
        Node eltPattern = pattern.getElement();
        Node idx = pattern.getIndex();

        if ( Node.ANY.equals(idx) )
            // ((element))[*] : any position.
            return Iter.flatMap(dElts.iterator(), elt->match(input, elt, eltPattern));

        idx = Var.lookup(input::get, idx);
        if ( Var.isVar(idx) ) {
            // ((element))[?i] : every position, binding ?i to the 1-based position.
            Var var = Var.alloc(idx);
            return Iter.flatMap(IntStream.range(0, dElts.size()).iterator(), i->{
                Binding b = BindingFactory.binding(input, var, NodeFactoryExtra.intToNode(i+1));
                return match(b, dElts.get(i), eltPattern);
            });
        }

        // ((element))[k] : one position.
        Integer position = positionValue(idx);
        if ( position == null || position < 1 || position > dElts.size() )
            return Iter.nullIterator();
        return match(input, dElts.get(position-1), eltPattern);
    }

    /** The value of a 1-based position index: an integer literal, else null. */
    /*package*/ static Integer positionValue(Node idx) {
        if ( idx == null || ! idx.isLiteral() )
            return null;
        try {
            Object value = idx.getLiteralValue();
            if ( value instanceof Number num )
                return num.intValue();
        } catch (DatatypeFormatException ex) { /* not a number */ }
        return null;
    }
}
