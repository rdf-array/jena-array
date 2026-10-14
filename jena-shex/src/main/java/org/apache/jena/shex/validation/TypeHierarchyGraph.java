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
package org.apache.jena.shex.validation;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.jena.graph.Node;
import org.apache.jena.shex.ShapeDecl;
import org.apache.jena.shex.calc.AccumulationUtil;
import org.apache.jena.shex.expressions.Shape;
import org.apache.jena.shex.expressions.ShapeExprRef;
import org.jgrapht.alg.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TypeHierarchyGraph {

    private DefaultDirectedGraph<Node, DefaultEdge> graph;
    private final Map<Node, ShapeDecl> shapeDeclMap;

    private TypeHierarchyGraph(Map<Node, ShapeDecl> shapeDeclMap){
        this.shapeDeclMap = shapeDeclMap;
    }

    public static TypeHierarchyGraph create (Map<Node, ShapeDecl> shapeDeclMap) {

        TypeHierarchyGraph result = new TypeHierarchyGraph(shapeDeclMap);
        result.graph = new DefaultDirectedGraph<>(DefaultEdge.class);

        shapeDeclMap.forEach((label, decl) -> result.graph.addVertex(decl.getLabel()));
        shapeDeclMap.forEach((label, decl) -> {
            // Only a declaration's OWN shapes contribute extends edges. Following
            // shape-expression references here (into other declarations' bodies)
            // made any shape that references a subtype of S a bogus subtype of S --
            // e.g. a catch-all start shape that references @<canonical> (which
            // EXTENDS <uri>) became a "subtype" of <uri>, and validating a node
            // against <uri> would pass via that catch-all, defeating CLOSED.
            List<Shape> accShapes = new ArrayList<>();
            AccumulationUtil.accumulateShapesInShapeExpr(decl.getShapeExpr(), accShapes);
            for (Shape shape : accShapes)
                for (ShapeExprRef extended : shape.getExtends())
                    result.graph.addEdge(decl.getLabel(), extended.getLabel());
        });
        // Remove the isolated vertices, ie those that do not participate in the type hierarchy
        List<Node> toBeRemoved = result.graph.vertexSet().stream()
                .filter(v -> result.graph.degreeOf(v) == 0)
                .collect(Collectors.toList());
        result.graph.removeAllVertices(toBeRemoved);
        return result;
    }

    public boolean hasCycles () {
        CycleDetector<Node, DefaultEdge> cycleDetector = new CycleDetector<>(graph);
        return cycleDetector.detectCycles();
    }

    /** All shape declarations that participate in the type hierarchy, i.e. extend something or are extended. */
    public Stream<Node> extendableShapeLabels() {
        return graph.vertexSet().stream();
    }

    /** True iff the label participates in the extension hierarchy (even if never extended). */
    public boolean isExtendableLabel(Node label) {
        return graph.vertexSet().contains(label);
    }

    /** Duplicates-free list of the non-abstract subtypes, including the given shape declaration. */
    public List<Node> getNonAbstractSubtypes(Node shexprLabel) {
        return nonAbstractAncestorsMap.computeIfAbsent(shexprLabel,
                label -> getAncestors(shexprLabel).stream()
                        .filter(l -> ! shapeDeclMap.get(l).isAbstract())
                        .collect(Collectors.toList()));
    }
    private final Map<Node, List<Node>> nonAbstractAncestorsMap = new HashMap<>();


    /* Duplicates-free list of the supertypes (ie extended shape declarations), including the given shape declaration. */
    public List<Node> getSupertypes(Node shexprLabel) {
        return supertypesMap.computeIfAbsent(shexprLabel,
                label -> new ArrayList<>(getDescendants(shexprLabel)));
    }
    private final Map<Node, List<Node>> supertypesMap = new HashMap<>();

    /** Returns the ancestors of a vertex, including the vertex itself. */
    private Set<Node> getAncestors (Node vertex) {
        return getClosure(vertex, graph::incomingEdgesOf, graph::getEdgeSource);
    }

    /** Returns the descendants of a vertex, including the vertex itself. */
    private Set<Node> getDescendants (Node vertex) {
        return getClosure(vertex, graph::outgoingEdgesOf, graph::getEdgeTarget);
    }

    /** Computes ancestors or descendants of a node provided the appropriate functions */
    private Set<Node> getClosure (Node vertex,
                                  Function<Node, Set<DefaultEdge>> adjacent,
                                  Function<DefaultEdge, Node> opposite) {
        if (! graph.containsVertex(vertex))
            return Set.of(vertex);

        Set<Node> result = new LinkedHashSet<>();
        Deque<Node> fifo = new ArrayDeque<>();

        result.add(vertex);
        fifo.addLast(vertex);
        while (! fifo.isEmpty()) {
            Node current = fifo.removeFirst();
            for (DefaultEdge adjEdge : adjacent.apply(current)) {
                Node other = opposite.apply(adjEdge);
                // Only enqueue newly-discovered vertices: if 'other' was already in 'result',
                // re-enqueueing it here would loop forever on any cycle in the graph (the
                // extends/type-hierarchy graph is not guaranteed acyclic before this runs).
                if ( result.add(other) )
                    fifo.addLast(other);
            }
        }
        return result;
    }

    public Iterator<? extends Pair<Node, Node>> edgesIterator() {
        return graph.edgeSet().stream()
                .map(e -> new ImmutablePair<>(graph.getEdgeSource(e), graph.getEdgeTarget(e))).iterator();
    }

}
