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
package org.apache.jena.shex.calc;

import org.apache.jena.graph.Node;
import org.apache.jena.shex.ShapeDecl;
import org.apache.jena.shex.expressions.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

public class AccumulationUtil {

    /** Accumulate the forward and backward predicates of a triple expression.
     * Follows triple expression references.
     * @param tripleExprs The triple expressions to explore
     * @param tripleExprRefsDefs Retrieves the definition of a triple expression reference
     * @param accFwdPredicates The collection to which the forward predicates are added
     * @param accInvPredicates The collection to which the inverse predicates are added
     */
    public static void accumulatePredicates(Collection<TripleExpr> tripleExprs,
                                            Function<Node, TripleExpr> tripleExprRefsDefs,
                                            Collection<Node> accFwdPredicates,
                                            Collection<Node> accInvPredicates) {

        TripleExprAccumulationVisitor<Node> fwdPredAccumulator = new TripleExprAccumulationVisitor<>(accFwdPredicates) {
            @Override
            public void visit(TripleConstraint tripleConstraint) {
                if (! tripleConstraint.isInverse())
                    accumulate(tripleConstraint.getPredicate());
            }
        };
        TripleExprAccumulationVisitor<Node> invPredAccumulator = new TripleExprAccumulationVisitor<>(accInvPredicates) {
            @Override
            public void visit(TripleConstraint tripleConstraint) {
                if (tripleConstraint.isInverse())
                    accumulate(tripleConstraint.getPredicate());
                super.visit(tripleConstraint);
            }
        };

        ExpressionWalker walker = ExpressionWalker.builder()
                .processTripleExprsWith(fwdPredAccumulator)
                .processTripleExprsWith(invPredAccumulator)
                .followTripleExprRefs(tripleExprRefsDefs)
                .build();
        tripleExprs.forEach(te -> te.visit(walker));
    }

    /** Accumulates the shape expression references that appear in a shape expression.
     * Explores recursively the shape expressions that appear as value expressions in triple constraints.
     * @param shapeExpr The shape expression to explore
     * @param acc The collection to which the references are added
     */
    public static void accumulateAllShapeExprRefsInShapeExpr (ShapeExpr shapeExpr, Collection<Node> acc) {
        ShapeExprAccumulationVisitor<Node> seAccVisitor = new ShapeExprRefAccumulationVisitor(acc);
        ExpressionWalker walker = ExpressionWalker.builder()
                .processShapeExprsWith(seAccVisitor)
                .traverseTripleConstraints()
                .traverseShapes()
                .build();
        shapeExpr.visit(walker);
    }

    /** Accumulates the triple expression references that appear in a shape expression.
     * Explores recursively the shape expressions that appear as value expressions in triple constraints.
     * @param shapeExpr The shape expression to explore
     * @param acc The collection to which the references are added
     */
    public static void accumulateAllTripleExprRefsInShapeExpr(ShapeExpr shapeExpr, Collection<Node> acc) {
        TripleExprRefAccumulationVisitor teAccVisitor = new TripleExprRefAccumulationVisitor(acc);
        ExpressionWalker walker = ExpressionWalker.builder()
                .processTripleExprsWith(teAccVisitor)
                .traverseShapes()
                .traverseTripleConstraints()
                .build();
        shapeExpr.visit(walker);
    }


    /** Accumulates the direct shape expression references that appear in a shape expression.
     * Does not traverse shapes, that is, does not explore recursively shape expressions that appear as value expressions in triple constraints
     * @param shapeExpr The shape expression to explore
     * @param acc The collection to which the references are added
     */
    public static void accumulateDirectShapeExprRefsInShapeExpr (ShapeExpr shapeExpr, Collection<Node> acc) {
        ShapeExprAccumulationVisitor<Node> seAccVisitor = new ShapeExprRefAccumulationVisitor(acc);
        ExpressionWalker walker = ExpressionWalker.builder()
                .processShapeExprsWith(seAccVisitor)
                .build();
        shapeExpr.visit(walker);
    }

    /** Accumulates the direct triple expression references that appear in a triple expression.
     * Does not traverse value expressions in triple constraints.
     * @param tripleExpr The shape expression to explore
     * @param acc The collection to which the references are added
     */
    public static void accumulateDirectTripleExprRefsInTripleExpr (TripleExpr tripleExpr, Collection<Node> acc) {
        TripleExprAccumulationVisitor<Node> teAccVisitor = new TripleExprRefAccumulationVisitor(acc);
        ExpressionWalker walker = ExpressionWalker.builder()
                .processTripleExprsWith(teAccVisitor)
                .build();
        tripleExpr.visit(walker);
    }


    /** Accumulates the direct triple constraints that appear in a triple expression.
     * Does not dereference the triple expression references
     * @param tripleExpr The triple expression to explore
     * @param acc The collection to which the triple constraints are added
     */
    public static void accumulateDirectTripleConstraints(TripleExpr tripleExpr, Collection<TripleConstraint> acc) {
        TripleConstraintAccumulationVisitor step = new TripleConstraintAccumulationVisitor(acc);
        ExpressionWalker walker = ExpressionWalker.builder()
                .processTripleExprsWith(step)
                .build();
        tripleExpr.visit(walker);
    }

    /** Accumulates the triple constraints that appear in a triple expression while dereferencing triple expression references.
     * @param tripleExpr The triple expression to explore
     * @param tripleExprRefsDefs Retrieves the definition of a triple expression reference
     * @param acc The collection to which the triple constraints are added
     */
    public static void accumulateTripleConstraintsFollowTripleExprReferences(TripleExpr tripleExpr,
                                                   Function<Node, TripleExpr> tripleExprRefsDefs,
                                                   Collection<TripleConstraint> acc) {

        TripleConstraintAccumulationVisitor step = new TripleConstraintAccumulationVisitor(acc);

        ExpressionWalker walker = ExpressionWalker.builder()
                .processTripleExprsWith(step)
                .followTripleExprRefs(tripleExprRefsDefs)
                .build();
        tripleExpr.visit(walker);
    }


    /** Accumulates the shapes that appear in a shape expression while dereferencing shape expression references.
     * @param shapeExpr The shape expression to explore
     * @param shapeExprRefsDefs Retrieves the definition of a shape expression reference
     * @param acc The collection to which the shapes are added
     */
    public static void accumulateShapesFollowShapeExprRefs (ShapeExpr shapeExpr,
                                                            Function<Node, ShapeDecl> shapeExprRefsDefs,
                                                            Collection<Shape> acc) {
        ShapeExprAccumulationVisitor<Shape> seVisitor = new ShapeExprAccumulationVisitor<>(acc) {
            @Override
            public void visit(Shape shape) {
                accumulate(shape);
            }
        };
        ExpressionWalker walker = ExpressionWalker.builder()
                .processShapeExprsWith(seVisitor)
                .followShapeExprRefs(shapeExprRefsDefs)
                .build();
        shapeExpr.visit(walker);
    }

    /** Accumulates the shapes that make up a shape expression's own structure
     * (ShapeAnd / ShapeOr / ShapeNot / Shape), WITHOUT dereferencing shape
     * expression references into other declarations. A reference to another
     * shape is not an extension of it, so this is what the type hierarchy needs
     * to read a declaration's own EXTENDS targets: following references instead
     * makes any shape that merely references a subtype of S a bogus subtype of S.
     * @param shapeExpr The shape expression to explore
     * @param acc The collection to which the shapes are added
     */
    public static void accumulateShapesInShapeExpr (ShapeExpr shapeExpr, Collection<Shape> acc) {
        ShapeExprAccumulationVisitor<Shape> seVisitor = new ShapeExprAccumulationVisitor<>(acc) {
            @Override
            public void visit(Shape shape) {
                accumulate(shape);
            }
        };
        ExpressionWalker walker = ExpressionWalker.builder()
                .processShapeExprsWith(seVisitor)
                .build();
        shapeExpr.visit(walker);
    }

    /** Collects the sub-expressions of a triple expression that have defined semantic actions.
     * Follows triple expression references.
     * @param tripleExpr The triple expression to explore
     * @param tripleExprRefsDefs Retrieves the definition of a triple expression reference
     * @return A list containing all sub-expressions that have semantic actions
     */
    public static List<TripleExpr> collectSubExprsWithSemActs(TripleExpr tripleExpr,
                                                              Function<Node, TripleExpr> tripleExprRefsDefs) {

        List<TripleExpr> result = new ArrayList<>();

        TripleExprAccumulationVisitor<TripleExpr> accumulator = new TripleExprAccumulationVisitor<>(result) {

            private void _visit(TripleExpr tripleExpr) {
                if (tripleExpr.getSemActs() != null)
                    accumulate(tripleExpr);
            }

            @Override
            public void visit(TripleExprCardinality tripleExprCardinality) {
                _visit(tripleExprCardinality);
            }

            @Override
            public void visit(EachOf eachOf) {
                _visit(eachOf);
            }

            @Override
            public void visit(OneOf oneOf) {
                _visit(oneOf);
            }

            @Override
            public void visit(TripleExprEmpty tripleExprEmpty) {}

            @Override
            public void visit(TripleExprRef tripleExprRef) {
                _visit(tripleExprRef);
            }

            @Override
            public void visit(TripleConstraint tripleConstraint) {
                _visit(tripleConstraint);
            }
        };

        ExpressionWalker walker = ExpressionWalker.builder()
                .processTripleExprsWith(accumulator)
                .followTripleExprRefs(tripleExprRefsDefs)
                .build();
        tripleExpr.visit(walker);
        return result;
    }



    // ----------------------------------------------------------------------------------------------
    // Visitors
    // ----------------------------------------------------------------------------------------------

    private static class ShapeExprRefAccumulationVisitor extends ShapeExprAccumulationVisitor<Node> {
        public ShapeExprRefAccumulationVisitor(Collection<Node> acc) {
            super(acc);
        }
        @Override
        public void visit(ShapeExprRef shapeExprRef) {
            accumulate(shapeExprRef.getLabel());
        }
    }

    private static class TripleExprRefAccumulationVisitor extends TripleExprAccumulationVisitor<Node> {
        public TripleExprRefAccumulationVisitor(Collection<Node> acc) {
            super(acc);
        }
        @Override
        public void visit(TripleExprRef tripleExprRef) {
            accumulate(tripleExprRef.getLabel());
        }
    }

    private static class TripleConstraintAccumulationVisitor extends TripleExprAccumulationVisitor<TripleConstraint> {
        public TripleConstraintAccumulationVisitor(Collection<TripleConstraint> acc) {
            super(acc);
        }
        @Override
        public void visit(TripleConstraint tripleConstraint) {
            accumulate(tripleConstraint);
        }
    };


}
