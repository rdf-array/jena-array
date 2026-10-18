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

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.shex.ShexSchemaStructureException;
import org.apache.jena.shex.calc.AccumulationUtil;
import org.apache.jena.shex.calc.TypedNodeConstraintComponentVisitor;
import org.apache.jena.shex.calc.TypedShapeExprVisitor;
import org.apache.jena.shex.calc.Util;
import org.apache.jena.shex.expressions.*;
import org.apache.jena.shex.reporting.Reporter;
import org.apache.jena.shex.sys.ShexLib;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.expr.nodevalue.NodeFunctions;

import java.util.*;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.apache.jena.shex.sys.ShexLib.displayStr;
import static org.apache.jena.shex.sys.ShexLib.strDatatype;

public class ShapeExprEval {

    public static boolean satisfies (Node dataNode, ShapeExpr expr,
                                     ValidationContext vCxt, Reporter reporter) {
        // Does not notify the reporter about conformant / non-conformant
        return _satisfies(dataNode, expr, null, vCxt, reporter)
            && vCxt.dispatchShapeExprSemanticAction(dataNode, expr, reporter);
    }

    private static boolean _satisfies(Node dataNode, ShapeExpr expr, Set<Triple> neigh,
                                      ValidationContext vCxt, Reporter reporter) {
        // Notifies the reporter about conformant / non-conformant.
        if (expr instanceof ShapeExprRef ref) {
            if (neigh == null)
                return vCxt.validate(dataNode, ref, reporter);
            else {
                List<Node> nonAbstractDescendants = vCxt.nonAbstractDescendants(ref.getLabel());

                // An abstract reference with no non-abstract descendant cannot be satisfied.
                if (nonAbstractDescendants.isEmpty()) {
                    reporter.informDescendantConformance(false, null);
                    return false;
                }

                boolean isDescendant = nonAbstractDescendants.get(0) != ref.getLabel(); /// <errorReporting/>

                for (Node descendant : nonAbstractDescendants) {
                    // TODO why not recurse on the reference here ? -> there is a reason I do not recall
                    ShapeExpr defn = vCxt.getDefinition(descendant);
                    // Only the parts of a proper descendant that actually extend the referenced
                    // label establish the node as that label (ShapeOr disjuncts that don't extend
                    // it are dropped).
                    if (!descendant.equals(ref.getLabel()))
                        defn = vCxt.restrictToExtending(defn, ref.getLabel());
                    Reporter defnReporter = reporter.createChild(dataNode, defn, neigh);

                    /// <errorReporting> the first element of nonAbstractDescendants is not a proper descendant
                    if (! isDescendant) isDescendant = true;  //
                    else defnReporter.informValidatingDescendant(descendant);
                    /// </errorReporting>

                    // TODO possible problem if the the definition is a reference itself
                    if (satisfiesNonRefExpr(dataNode, defn, neigh, vCxt, defnReporter)) {
                        reporter.informDescendantConformance(true, descendant);
                        return true;
                    }
                }
                if (nonAbstractDescendants.size() > 1 || vCxt.getShapeDecl(ref.getLabel()).isAbstract()) {
                    reporter.informDescendantConformance(false, null);
                }
                return false;
            }
        } else {
            return satisfiesNonRefExpr(dataNode, expr, neigh, vCxt, reporter);
        }
    }

    private static boolean satisfiesNonRefExpr (Node node, ShapeExpr expr, Set<Triple> neigh,
                                                ValidationContext vCxt, Reporter reporter) {
        // Does not directly notify the reporter about conformant / non-conformant. Should be done by the visit method
        ShapeExprEvalVisitor evaluator = new ShapeExprEvalVisitor(node, neigh, vCxt);
        return expr.visit(evaluator, reporter);
    }

    /** Validates a node's neighbourhood or a set of triples against a shape.
     * If triples is null, the whole node's neighbourhood is considered. */
    private static boolean satisfiesShape(Node dataNode,
                                          Shape shape,
                                          Set<Triple> triples,
                                          ValidationContext vCxt,
                                          Reporter shapeReporter) {
        // Notifies the reporter about conformant / non-conformant.

        // 1. Collect each supertype's definition (deduped: a diamond is processed once).
        //    A supertype defined as a ShapeOr is a choice point: the node satisfies the
        //    extension through exactly one disjunct, and the closed check must see only that
        //    disjunct's predicates.  So the ShapeOr disjuncts are enumerated (the cartesian
        //    product over all ShapeOr supertypes) and each combination is tried; the node
        //    conforms if any combination does.  Shape and ShapeAnd supertypes contribute a
        //    single alternative each (see addSupertypeContribution).
        LinkedHashMap<Node, ShapeExpr> superDefs = new LinkedHashMap<>();
        for (ShapeExprRef ref: shape.getExtends())
            for (Node superType: vCxt.getSupertypes(ref.getLabel()))
                superDefs.putIfAbsent(superType, vCxt.getDefinition(superType));

        List<Node> superTypesList = new ArrayList<>(superDefs.keySet());
        List<List<ShapeExpr>> alternatives = new ArrayList<>();
        long combos = 1;
        for (Node st : superTypesList) {
            ShapeExpr def = Util.dereference(superDefs.get(st), vCxt::getDefinition);
            List<ShapeExpr> alts = (def instanceof ShapeOr so)
                    ? new ArrayList<>(so.getShapeExprs())
                    : List.of(def);
            alternatives.add(alts);
            combos *= alts.size();
        }

        if (combos == 1) {
            // Common case: no ShapeOr supertype.  One combination, on the shape's own reporter.
            Map<Node, TripleExpr> mainTripleExprs = new HashMap<>();
            Map<Node, List<ShapeExpr>> constraints = new HashMap<>();
            mainTripleExprs.put(null, shape.getTripleExpr());
            for (int i = 0; i < superTypesList.size(); i++)
                addSupertypeContribution(superTypesList.get(i), alternatives.get(i).get(0),
                        mainTripleExprs, constraints, vCxt);
            return satisfiesWithExprs(dataNode, shape, triples, mainTripleExprs, constraints, vCxt, shapeReporter);
        }

        // ShapeOr supertype(s): try each combination of disjunct choices (first that conforms wins).
        for (long c = 0; c < combos; c++) {
            Map<Node, TripleExpr> mainTripleExprs = new HashMap<>();
            Map<Node, List<ShapeExpr>> constraints = new HashMap<>();
            mainTripleExprs.put(null, shape.getTripleExpr());
            long rem = c;
            for (int i = 0; i < superTypesList.size(); i++) {
                List<ShapeExpr> alts = alternatives.get(i);
                ShapeExpr chosen = alts.get((int) (rem % alts.size()));
                rem /= alts.size();
                addSupertypeContribution(superTypesList.get(i), chosen, mainTripleExprs, constraints, vCxt);
            }
            Reporter comboReporter = shapeReporter.createChild(dataNode, shape, triples);
            if (satisfiesWithExprs(dataNode, shape, triples, mainTripleExprs, constraints, vCxt, comboReporter))
                return shapeReporter.setIsConformant(true);
        }
        return shapeReporter.setIsConformant(false);
    }

    /** Add one supertype's contribution to the main triple expressions (visible to the
     *  partition) and the post-partition constraints.  The definition is a Shape or a
     *  ShapeAnd; a ShapeOr supertype is split into disjuncts by the caller, each disjunct
     *  arriving here.  For a ShapeAnd, every Shape conjunct contributes its triple expression
     *  (combined into an EachOf, the first participating normally and the rest wrapped {0,inf}
     *  so their predicates are matchable without forcing extra allocation); all conjuncts are
     *  kept as post-partition constraints. */
    private static void addSupertypeContribution(Node superType, ShapeExpr def,
                                                 Map<Node, TripleExpr> mainTripleExprs,
                                                 Map<Node, List<ShapeExpr>> constraints,
                                                 ValidationContext vCxt) {
        ShapeExpr d = Util.dereference(def, vCxt::getDefinition);
        if (d instanceof Shape sh) {
            mainTripleExprs.put(superType, sh.getTripleExpr());
            constraints.put(superType, List.of());
        } else if (d instanceof ShapeAnd sa) {
            List<TripleExpr> allShapeTEs = new ArrayList<>();
            for (ShapeExpr conjunct : sa.getShapeExprs()) {
                ShapeExpr deref = Util.dereference(conjunct, vCxt::getDefinition);
                if (deref instanceof Shape shConj)
                    allShapeTEs.add(shConj.getTripleExpr());
            }
            if (allShapeTEs.isEmpty())
                throw new ShexSchemaStructureException(
                    "Extending shape " + superType + " has no Shape conjunct");
            TripleExpr combined;
            if (allShapeTEs.size() == 1) {
                combined = allShapeTEs.get(0);
            } else {
                List<TripleExpr> parts = new ArrayList<>();
                parts.add(allShapeTEs.get(0));
                for (int i = 1; i < allShapeTEs.size(); i++)
                    parts.add(TripleExprCardinality.create(allShapeTEs.get(i), Cardinality.STAR, List.of()));
                combined = EachOf.create(parts, List.of());
            }
            mainTripleExprs.put(superType, combined);
            constraints.put(superType, new ArrayList<>(sa.getShapeExprs()));
        } else {
            throw new ShexSchemaStructureException(
                "Extending shape " + superType + " is neither a Shape nor a ShapeAnd");
        }
    }

    /** Steps 2-4: extract the relevant neighbourhood, check closedness, and look for a split
     *  of the triples among the hierarchy's main triple expressions that also satisfies the
     *  per-supertype constraints. */
    private static boolean satisfiesWithExprs(Node dataNode, Shape shape, Set<Triple> triples,
                                              Map<Node, TripleExpr> mainTripleExprs,
                                              Map<Node, List<ShapeExpr>> constraints,
                                              ValidationContext vCxt, Reporter shapeReporter) {
        // 2. Extract the neighbourhood of the node relevant for satisfying that shape
        Set<Triple> matchables = new HashSet<>();
        Set<Triple> nonMatchables = new HashSet<>();
        if (null == triples) {  // Validating the whole neighbourhood
            Util.retrieveRelevantNeighbourhood(vCxt.getGraph(), dataNode,
                    mainTripleExprs.values(), matchables, nonMatchables, vCxt);
        } else {   // Validating only part of the neighbourhood -- filter by predicate just like above
            Set<Node> fwdPredicates = new HashSet<>();
            Set<Node> invPredicates = new HashSet<>();
            AccumulationUtil.accumulatePredicates(mainTripleExprs.values(),
                    vCxt::getTripleExpr, fwdPredicates, invPredicates);
            Util.filterNeighbourhoodForPredicates(triples, dataNode, fwdPredicates, invPredicates, matchables);
            triples.forEach(t -> { if (!matchables.contains(t)) nonMatchables.add(t); });
        }

        // 3. Check if the closed constraint is satisfied, if any
        if (shape.isClosed() && !nonMatchables.isEmpty()) {
            shapeReporter.informUnexpectedTriples(nonMatchables, Reporter.UnexpectedTriplesReason.CLOSED);
            return shapeReporter.setIsConformant(false);
        }

        // 4. Iterate over splits that satisfy the main triple expressions of the hierarchy, and look for a split
        //    that satisfies the constraints.
        Iterator<Map<Node, Set<Triple>>> splitsIt = TripleExprEval.correctSplitsIterator(dataNode, shape, matchables,
                mainTripleExprs, vCxt, shapeReporter);
        while (splitsIt.hasNext()) {
            Map<Node, Set<Triple>> split = splitsIt.next();
            if (splitSatisfiesConstraints(dataNode, shape, split, constraints, vCxt, shapeReporter)) {
                return shapeReporter.setIsConformant(true);
            }
        }
        return shapeReporter.setIsConformant(false);
    }

    /** Check whether a splitting of the triples between the supertypes also satisfies the constraints
     * of each supertype. */
    private static boolean splitSatisfiesConstraints (Node dataNode,
                                                      Shape shape,
                                                      Map<Node, Set<Triple>> split,
                                                      Map<Node, List<ShapeExpr>> constraints,
                                                      ValidationContext vCxt,
                                                      Reporter shapeReporter) {
        // Does not directly notify the reporter about conformant / non-conformant.
        Map<Node, Set<Triple>> relevantTriples = new HashMap<>();
        for (Map.Entry<Node, List<ShapeExpr>> e : constraints.entrySet()) {
            for (ShapeExpr constr : e.getValue()) {
                Set<Triple> triples = relevantTriples.computeIfAbsent(e.getKey(),
                        k -> vCxt.getSupertypes(k).stream()
                            .flatMap(l -> split.get(l).stream())
                            .collect(Collectors.toSet()));
                Reporter subReporter = shapeReporter.createChild(dataNode, constr, triples);
                if (!_satisfies(dataNode, constr, triples, vCxt, subReporter)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean satisfiesNodeConstraint(NodeConstraint nodeConstraint,
                                                   Node dataNode,
                                                   Reporter reporter) {
        // Notifies the reporter about conformant / non-conformant
        NodeConstraintComponentEvalVisitor componentEval =
                new NodeConstraintComponentEvalVisitor(dataNode, reporter);
        return reporter.setIsConformant(nodeConstraint.getComponents().stream()
                .allMatch(ncc -> ncc.visit(componentEval)));
    }

    private static class ShapeExprEvalVisitor implements TypedShapeExprVisitor<Boolean, Reporter> {

        private final ValidationContext vCxt;
        private final Node dataNode;
        private final Set<Triple> triples;

        ShapeExprEvalVisitor(Node dataNode, Set<Triple> triples, ValidationContext vCxt) {
            this.vCxt = vCxt;
            this.dataNode = dataNode;
            this.triples = triples;
        }

        @Override
        public Boolean visit(ShapeAnd shapeAnd, Reporter reporter) {
            for (ShapeExpr se : shapeAnd.getShapeExprs()) {
                if (! se.visit(this, reporter.createChild(dataNode, se, triples))) {
                    return reporter.setIsConformant(false);
                }
            }
            return reporter.setIsConformant(true);
        }

        @Override
        public Boolean visit(ShapeOr shapeOr, Reporter reporter) {
            for (ShapeExpr se : shapeOr.getShapeExprs()) {
                if (se.visit(this, reporter.createChild(dataNode, se, triples))) {
                    return reporter.setIsConformant(true);
                }
            }
            return reporter.setIsConformant(false);
        }

        @Override
        public Boolean visit(ShapeNot shapeNot, Reporter reporter) {
            ShapeExpr subExpr = shapeNot.getShapeExpr();
            boolean subExprIsValid = subExpr.visit(this, reporter.createChild(dataNode, subExpr, triples));
            return reporter.setIsConformant(! subExprIsValid);
        }

        @Override
        public Boolean visit(ShapeExprRef shapeExprRef, Reporter reporter) {
            return _satisfies(dataNode, shapeExprRef, triples, vCxt, reporter);
        }

        @Override
        public Boolean visit(ShapeCollection shapeCollection, Reporter reporter) {
            // RDF sequences: the focus node must be a sequence of an acceptable length,
            // each element satisfying the element shape expression (as a focus node of its own).
            if ( ! dataNode.isNodeSeq() ) {
                reporter.addInfo("Collection: not a sequence: " + ShexLib.displayStr(dataNode), null);
                return reporter.setIsConformant(false);
            }
            List<Node> elements = dataNode.getSeq();
            if ( ! shapeCollection.acceptsSize(elements.size()) ) {
                reporter.addInfo("Collection: " + elements.size() + " elements, bounds " + shapeCollection.boundsString(), null);
                return reporter.setIsConformant(false);
            }
            ShapeExpr eltExpr = shapeCollection.getShapeExpr();
            for ( Node elt : elements ) {
                Reporter eltReporter = reporter.createChild(elt, eltExpr, null);
                if ( ! satisfies(elt, eltExpr, vCxt, eltReporter) ) {
                    reporter.addInfo("Collection: element does not satisfy the element shape expression: " + ShexLib.displayStr(elt), null);
                    return reporter.setIsConformant(false);
                }
            }
            return reporter.setIsConformant(true);
        }

        @Override
        public Boolean visit(ShapeExternal shapeExternal, Reporter reporter) {
            // TODO shape external never satisfied
            reporter.informExternalNotSupported();
            return reporter.setIsConformant(false);
        }

        @Override
        public Boolean visit(Shape shape, Reporter reporter) {
            return satisfiesShape(dataNode, shape, triples, vCxt, reporter);
        }

        @Override
        public Boolean visit(NodeConstraint nodeConstraint, Reporter reporter) {
            return satisfiesNodeConstraint(nodeConstraint, dataNode, reporter);
        }
    }

    private static class NodeConstraintComponentEvalVisitor implements TypedNodeConstraintComponentVisitor<Boolean> {

        private final Node dataNode;
        private final Reporter reporter;

        NodeConstraintComponentEvalVisitor(Node dataNode, Reporter reporter) {
            this.dataNode = dataNode;
            this.reporter = reporter;
        }

        @Override
        public Boolean visit(NodeKindConstraint nodeKindCstr) {
            NodeKind nodeKind = nodeKindCstr.getNodeKind();
            boolean satisfied = switch (nodeKind) {
                case BNODE -> dataNode.isBlank();
                case IRI -> dataNode.isURI();
                case LITERAL -> dataNode.isLiteral();
                case NONLITERAL -> !dataNode.isLiteral();
                default -> true;
            };

            // TODO Bad.
            if (!satisfied)
                reporter.informInvalidNodeConstraintComponent(nodeKindCstr,
                        nodeKindCstr + ": Expected " + nodeKind + " for " + displayStr(dataNode));
            return satisfied;
        }

        @Override
        public Boolean visit(DatatypeConstraint datatypeCstr) {
            if (!dataNode.isLiteral()) {
                reporter.informInvalidNodeConstraintComponent(datatypeCstr,
                        datatypeCstr + " : Not a literal");
                return false;
            }

            if (datatypeCstr.getDatatypeURI().equals(dataNode.getLiteralDatatypeURI())) {
                // Must be valid for the type
                if (!datatypeCstr.getRDFDatatype().isValid(dataNode.getLiteralLexicalForm())) {
                    reporter.informInvalidNodeConstraintComponent(datatypeCstr,
                            datatypeCstr + " : Not valid value : Node " + displayStr(dataNode));
                    return false;
                }
            } else {
                reporter.informInvalidNodeConstraintComponent(datatypeCstr,
                        datatypeCstr + " -- Wrong datatype: " + strDatatype(dataNode) + " for focus node: " + displayStr(dataNode));
                return false;
            }
            return true;
        }

        @Override
        public Boolean visit(NumLengthConstraint numLengthCstr) {
            if (!dataNode.isLiteral()) {
                reporter.informInvalidNodeConstraintComponent(numLengthCstr, format("NumericConstraint: Not numeric: %s ", ShexLib.displayStr(dataNode)));
                return false;
            }

            RDFDatatype rdfDT = dataNode.getLiteralDatatype();
            if (!(rdfDT instanceof XSDDatatype)) {
                reporter.informInvalidNodeConstraintComponent(numLengthCstr,
                        format("NumericConstraint: Not a numeric: %s ", ShexLib.displayStr(dataNode)));
                return false;
            }

            if (XSDDatatype.XSDfloat.equals(rdfDT) || XSDDatatype.XSDdouble.equals(rdfDT)) {
                reporter.informInvalidNodeConstraintComponent(numLengthCstr,
                        format("NumericConstraint: Numeric not compatible with xsd:decimal: %s ", ShexLib.displayStr(dataNode)));
                return false;
            }
            String lexicalForm = dataNode.getLiteralLexicalForm();
            if (!rdfDT.isValid(lexicalForm)) {
                reporter.informInvalidNodeConstraintComponent(numLengthCstr,
                        format("NumericConstraint: Not a valid xsd:decimal: %s ", ShexLib.displayStr(dataNode)));
                return false;
            }

            int N = lexicalForm.length();
            int idx = lexicalForm.indexOf('.');

            switch (numLengthCstr.getLengthType()) {
                case FRACTIONDIGITS: {
                    // Does not include trailing zeros.
                    if (idx < 0) {
                        return true;
                    }
                    //int before = idx;
                    int after = lexicalForm.length() - idx - 1;
                    for (int i = N - 1; i > idx; i--) {
                        if (lexicalForm.charAt(i) != '0')
                            break;
                        after--;
                    }
                    if (after <= numLengthCstr.getLength()) {
                        return true;
                    }
                    break;
                }
                case TOTALDIGITS: {
                    // Canonical form.
                    int start = 0;
                    char ch1 = lexicalForm.charAt(0);
                    if (ch1 == '+' || ch1 == '-')
                        start++;
                    // Leading zeros
                    for (int i = start; i < N; i++) {
                        if (lexicalForm.charAt(i) != '0')
                            break;
                        start++;
                    }
                    int finish = N;
                    // Trailing zeros
                    if (idx >= 0) {
                        finish--;
                        for (int i = N - 1; i > idx; i--) {
                            if (lexicalForm.charAt(i) != '0')
                                break;
                            finish--;
                        }
                    }
                    int digits = finish - start;

                    if (digits <= numLengthCstr.getLength()) {
                        return true;
                    }
                    break;
                }
                default:
                    break;
            }

            String msg = format("Expected %s %d : got = %d", numLengthCstr.getLengthType().label(),
                    numLengthCstr.getLength(), lexicalForm.length());
            reporter.informInvalidNodeConstraintComponent(numLengthCstr, msg);
            return false;
        }

        @Override
        public Boolean visit(NumRangeConstraint numRangeCstr) {
            if (!dataNode.isLiteral()) {
                reporter.informInvalidNodeConstraintComponent(numRangeCstr, "NumRange: Not a literal number");
                return false;
            }
            NodeValue nv = NodeValue.makeNode(dataNode);
            int r = NodeValue.compare(nv, numRangeCstr.getNumericValue());

            switch (numRangeCstr.getRangeKind()) {

                case MAXEXCLUSIVE:
                    if (r < 0) {
                        return true;
                    }
                    break;
                case MAXINCLUSIVE:
                    if (r <= 0) {
                        return true;
                    }
                    break;
                case MINEXCLUSIVE:
                    if (r > 0) {
                        return true;
                    }
                    break;
                case MININCLUSIVE:
                    if (r >= 0) {
                        return true;
                    }
                    break;
            }
            String msg = format("Expected %s %s : got = %s", numRangeCstr.getRangeKind().label(), NodeFmtLib.strTTL(nv.getNode()), NodeFmtLib.strTTL(dataNode));
            reporter.informInvalidNodeConstraintComponent(numRangeCstr, msg);
            return false;
        }

        @Override
        public Boolean visit(StrRegexConstraint strRegexCstr) {
            if (dataNode.isBlank()) {
                String msg = toString() + ": Blank node: " + displayStr(dataNode);
                reporter.informInvalidNodeConstraintComponent(strRegexCstr, msg);
                return false;
            }
            String str = NodeFunctions.str(dataNode);
            if (strRegexCstr.getPattern().matcher(str).find()) {
                return true;
            }
            String msg = strRegexCstr + ": Does not match: '" + str + "'";
            reporter.informInvalidNodeConstraintComponent(strRegexCstr, msg);
            return false;
        }

        @Override
        public Boolean visit(StrLengthConstraint strLengthCstr) {
            StrLengthKind lengthType = strLengthCstr.getLengthType();
            int length = strLengthCstr.getLength();
            if (!dataNode.isLiteral() && !dataNode.isURI()) {
                String msg = format("%s: Not a literal or URI: %s", lengthType.label(), ShexLib.displayStr(dataNode));
                reporter.informInvalidNodeConstraintComponent(strLengthCstr, msg);
                return false;
            }
            String str = NodeFunctions.str(dataNode);
            switch (lengthType) {
                case LENGTH:
                    if (str.length() == length) {
                        return true;
                    }
                    break;
                case MAXLENGTH:
                    if (str.length() <= length) {
                        return true;
                    }
                    break;
                case MINLENGTH:
                    if (str.length() >= length) {
                        return true;
                    }
                    break;
            }

            String msg = format("Expected %s %d : got = %d", lengthType.label(), length, str.length());
            reporter.informInvalidNodeConstraintComponent(strLengthCstr, msg);
            return false;
        }

        @Override
        public Boolean visit(ValueConstraint valueCstr) {
            boolean b = valueCstr.getValueSetRanges().stream()
                    .anyMatch(valueSetRange -> validateRange(valueSetRange, dataNode));
            if (!b) {
                reporter.informInvalidNodeConstraintComponent(valueCstr, "Value " + ShexLib.displayStr(dataNode) + " not in range: " + valueCstr);
                return false;
            }
            return true;
        }

        private boolean validateRange(ValueSetRange valueSetRange, Node data) {
            boolean b1 = valueSetRange.included(data);
            if (!b1)
                return false;
            return ! valueSetRange.excluded(data);
            // OK
        }
    }


}
