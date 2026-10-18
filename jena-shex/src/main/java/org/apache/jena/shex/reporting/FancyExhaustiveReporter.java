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
package org.apache.jena.shex.reporting;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.shex.calc.ExpressionWalker;
import org.apache.jena.shex.calc.TripleExprAccumulationVisitor;
import org.apache.jena.shex.expressions.*;
import org.apache.jena.shex.validation.EMap;
import org.apache.jena.shex.validation.TripleExprForValidation;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class FancyExhaustiveReporter extends SimpleExhaustiveReporter {

    private static final FancyExhaustiveReporter factoryInstance = new FancyExhaustiveReporter();
    public static FancyExhaustiveReporter factory() {
        return factoryInstance;
    }
    private FancyExhaustiveReporter() {
        super();
    }

    protected FancyExhaustiveReporter(Node node, Expression expr, Set<Triple> neigh) {
        report = new ExpressionHReport(node, expr, neigh);
    }

    @Override
    public FancyExhaustiveReporter createRoot(Node node, ShapeExprRef expr) {
        return new FancyExhaustiveReporter(node, expr, null);
    }

    @Override
    public FancyExhaustiveReporter createChild(Node node, Expression expr, Set<Triple> neigh) {
        FancyExhaustiveReporter r = new FancyExhaustiveReporter(node, expr, neigh);
        report.addChild(r.report);
        return r;
    }

    // -----------------------------------------------------------
    // Attributes for reporting errors in Shape
    // -----------------------------------------------------------
    private Map<Node, TripleExprForValidation> shapeTripleExpressions = null;
    private Map<Triple, List<TripleConstraint>> lastNonConformantPreMatching = null;
    private Map<Triple, TripleConstraint> lastNonConformantMatching = null;
    private Reporter.MatchingNotSatisfiedReason lastNonConformantMatchingReason = null;

    @Override
    public void informCandidateMatchingConformance(boolean isConformant, Map<Triple, TripleConstraint> matching,
                                                   Reporter.MatchingNotSatisfiedReason reasonIfNonConformant) {
        if (! isConformant) {
            this.lastNonConformantMatching = matching;
            this.lastNonConformantMatchingReason = reasonIfNonConformant;
        }
    }

    @Override
    public void informShapeTripleExpressions (Map<Node, TripleExprForValidation> expressions) {
        this.shapeTripleExpressions = expressions;
    }

    @Override
    public void informPreMatching(Map<Triple, List<TripleConstraint>> cleanPreMatching, Boolean allowsConformance) {
        if (allowsConformance != null && ! allowsConformance)
            this.lastNonConformantPreMatching = new HashMap<>(cleanPreMatching);
    }

    @Override
    public void informUnmatchedTripleConstraints (List<TripleConstraint> tripleConstraints) {
        addInfo("These triple constraints could not be satisfied.", tripleConstraints);
    }

    // -----------------------------------------------------------------------------
    // Generate the error message
    // -----------------------------------------------------------------------------
    @Override
    public boolean setIsConformant (boolean isConformant) {
        if (! isConformant) {
            if (report.expr instanceof Shape) {
                reportShapeErrors();
            } else if (report.expr instanceof ShapeAnd) {
                addInfo("One of the conjuncts of ShapeAnd was not satisfied.", null);
            } else if (report.expr instanceof ShapeOr) {
                addInfo("None of the disjuncts of ShapeOr was satisfied.", null);
            } else if (report.expr instanceof ShapeNot) {
                addInfo("A negated shape expression was satisfied.", null);
            } else if (report.expr instanceof ShapeCollection) {
                addInfo("A collection shape expression was not satisfied.", null);
            }
        }
        return super.setIsConformant(isConformant);
    }

    /** Reports for some kinds of errors when a shape is not satisfied. */
    private void reportShapeErrors() {
        boolean errorFound = false;

        // Conditions under which we search specific errors:
        //   the triple expression was not matched, and the triple expression is deterministic
        if (lastNonConformantMatchingReason == MatchingNotSatisfiedReason.TRIPLE_EXPRS) {
            if (isPreMatchingDeterministic(lastNonConformantPreMatching, shapeTripleExpressions)) {

                // The kinds of specific errors we are looking for
                errorFound |= reportSimpleCardinalityErrors();
                errorFound |= reportOneOfErrors();
            }
            errorFound |= reportSimpleEachOfErrors();
        }

        if (!errorFound && lastNonConformantMatchingReason == MatchingNotSatisfiedReason.TRIPLE_EXPRS)
            // If no specific error found, then add a generic errer message
            super.informCandidateMatchingConformance(false, lastNonConformantMatching, lastNonConformantMatchingReason);
    }

    /** Determines whether the pre-matching associates at most one triple constraint with every triple.
     * Considers the triple constraints of the original triple expression (i.e. not the sorbe expression). */
    private boolean isPreMatchingDeterministic(Map<Triple, List<TripleConstraint>> preMatching,
                                               Map<Node, TripleExprForValidation> expressions) {
        // If the expression is not natively SORBE, it's not considered deterministic
        for (TripleExprForValidation teVal : expressions.values())
            if (! teVal.isNativeSorbe())
                return false;
        for (List<TripleConstraint> l : preMatching.values())
            if (l.size() > 1)
                return false;
        return true;
    }

    /** A simple cardinality error is about triple constraints that have a directly attached cardinality (or default 1)
     * and the number of triples that matched is incorrect.
     * Reports the error, and returns whether some error was found or reported. */
    private boolean reportSimpleCardinalityErrors() {
        AtomicBoolean hasError = new AtomicBoolean(false);
        Map<TripleConstraint, List<Triple>> invertedMatching = lastNonConformantMatching.entrySet()
                .stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getValue,                   // group by value
                        Collectors.mapping(Map.Entry::getKey,  // collect keys
                                Collectors.toList())
                ));
        Map<TripleConstraint, Cardinality> cards = cardinalities(shapeTripleExpressions.values());
        cards.forEach((tc, card) -> {
            int nb = invertedMatching.getOrDefault(tc, Collections.emptyList()).size();
            if (nb < card.min || nb > card.max) {
                String m = String.format(
                        "Cardinality error. Incorrect number of triples matched a triple expression. Expected: between %d and %d; found: %d",
                        card.min, card.max, nb);
                addInfo(m, Pair.of(tc, invertedMatching.getOrDefault(tc, Collections.emptyList())));
                hasError.set(true);
            }
        });
        return hasError.get();
    }

    private boolean reportOneOfErrors() {
        // Two kinds of errors: 1) several choices satisfied, 2) none satisfied
        boolean errorFound = false;
        for (TripleExprForValidation teVal : shapeTripleExpressions.values()) {
            for (OneOf oneOf: oneOfs(teVal)) {
                List<TripleExpr> satisfiedSubExprs = new ArrayList<>();
                for (TripleExpr e: oneOf.getTripleExprs()) {
                    if (teVal.subExprIsValid(e, lastNonConformantMatching)) {
                        satisfiedSubExprs.add(e);
                    }
                }
                if (satisfiedSubExprs.isEmpty()) {
                    addInfo("None of the choices of a OneOf is satisfied.", oneOf);
                    errorFound = true;
                } else if (satisfiedSubExprs.size() > 1) {
                    addInfo("Several choices of a OneOf are satisfied.", satisfiedSubExprs);
                    errorFound = true;
                }

            }
        }
        return errorFound;
    }

    private boolean reportSimpleEachOfErrors() {
        boolean errorFound = false;
        for (TripleExprForValidation teVal : shapeTripleExpressions.values()) {
            for (EachOf eachOf: eachOfs(teVal)) {
                List<TripleExpr> nonSatisfiedSubExprs = new ArrayList<>();
                for (TripleExpr e: eachOf.getTripleExprs())
                    if (! teVal.subExprIsValid(e, lastNonConformantMatching))
                        nonSatisfiedSubExprs.add(e);

                if (! nonSatisfiedSubExprs.isEmpty()) {
                    //nonSatisfiedSubExprs.add(0, eachOf);
                    addInfo("These sub-expressions of EachOf are not satisfied.", nonSatisfiedSubExprs);
                }
            }
        }
        return errorFound;
    }

    /** Returns a map containing the triple constraints that have a directly attached cardinality,
     * or that are on the top level EachOf with a default one cardinality. */
    private static Map<TripleConstraint, Cardinality> cardinalities (Collection<TripleExprForValidation> expressions) {
        List<Pair<TripleConstraint, Cardinality>> cardPairs = new ArrayList<>(1);
        TripleExprAccumulationVisitor<Pair<TripleConstraint, Cardinality>> cardinalityFinder =
                new TripleExprAccumulationVisitor<>(cardPairs) {
            @Override
            public void visit(TripleExprCardinality te) {
                if (te.getSubExpr() instanceof TripleConstraint)
                    accumulate(Pair.of((TripleConstraint) te.getSubExpr(), te.getCardinality()));
            }

            @Override
            public void visit(TripleConstraint tc) {
                accumulate(Pair.of(tc, Cardinality.ONE));
            }
        };
        ExpressionWalker walker = ExpressionWalker.builder()
                .processTripleExprsWith(cardinalityFinder)
                .dontRecurseInto(TripleExprCardinality.class)
                .dontRecurseInto(OneOf.class)
                .build();
        expressions.forEach(te -> te.getOriginalExpr().visit(walker));
        EMap<TripleConstraint, Cardinality> result = new EMap<>();
        for (Pair<TripleConstraint, Cardinality> p: cardPairs)
            result.put(p.getLeft(), p.getRight());
        return result;
    }




    private List<OneOf> oneOfs(TripleExprForValidation teVal) {
        List<OneOf> result = new ArrayList<>();
        TripleExprAccumulationVisitor<OneOf> oneOfFinder =
                new TripleExprAccumulationVisitor<>(result) {
                    @Override
                    public void visit(OneOf te) {
                        accumulate(te);
                    }
                };
        ExpressionWalker walker = ExpressionWalker.builder()
                .processTripleExprsWith(oneOfFinder)
                .dontRecurseInto(TripleExprCardinality.class)
                .dontRecurseInto(OneOf.class)   // TODO why ?
                .build();
        teVal.getOriginalExpr().visit(walker);
        return result;
    }

    private List<EachOf> eachOfs(TripleExprForValidation teVal) {
        List<EachOf> result = new ArrayList<>();
        TripleExprAccumulationVisitor<EachOf> eachOfFinder =
                new TripleExprAccumulationVisitor<>(result) {
                    @Override
                    public void visit(EachOf te) {
                        accumulate(te);
                    }
                };
        ExpressionWalker walker = ExpressionWalker.builder()
                .processTripleExprsWith(eachOfFinder)
                .dontRecurseInto(TripleExprCardinality.class)
                .dontRecurseInto(EachOf.class)  // TODO why ?
                .build();
        teVal.getOriginalExpr().visit(walker);
        return result;
    }


}
