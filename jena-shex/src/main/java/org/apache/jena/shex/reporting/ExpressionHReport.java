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


import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.shex.ShexStatus;
import org.apache.jena.shex.expressions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Hierarchic report on validating a node or a neighbourhood against as {@link Expression}.
 */
public class ExpressionHReport implements Report {


    protected final Node node;
    protected final Expression expr;
    private final Set<Triple> subNeigh;
    private ShexStatus status;

    private final List<ExpressionHReport> children = new ArrayList<>();
    private final List<ReportInfo> infos = new ArrayList<>();
    private Report refersTo = null;

    /* package */ ExpressionHReport(Node node, Expression expr, Set<Triple> neighbourhood) {
        this.node = node;
        this.expr = expr;
        this.subNeigh = neighbourhood;
    }

    public ShexStatus getStatus() {
        return status;
    }

    public void asReferenceTo(Report report, String additionalMessage) {
        this.refersTo = report;
    }

    public List<ExpressionHReport> getChildren() {
        if (refersTo == null) {
            return Collections.unmodifiableList(children);
        }
        return Collections.emptyList();
    }

    public List<ReportInfo> getInfos() {
        if (refersTo == null)
            return Collections.unmodifiableList(infos);
        return Collections.emptyList();
    }



    /* package */ void addChild(ExpressionHReport child) {
        if (refersTo != null)
            throw new UnsupportedOperationException("Reference report cannot be modified.");
        children.add(child);
    }

    /* package */ void addInfo(ReportInfo info) {
        if (refersTo != null)
            throw new UnsupportedOperationException("Reference report cannot be modified.");
        infos.add(info);
    }

    @Override
    public String toString() {
        if (refersTo == null)
            return toString(this, 4);
        return refersTo.toString();
    }

    private static String toString(ExpressionHReport r, int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(" ".repeat(indent));
        sb.append("- ");
        String sNeigh = r.getSubNeigh() == null ? "" :
                String.format("neigh:%s", r.getSubNeigh());
        sb.append(String.format("%s %s ?? %s %s",
                r.status == ShexStatus.conformant ? "OK" : "KO",
                r.node,
                exprToPrettyString(r.expr),
                sNeigh));
        for (ReportInfo info : r.infos) {
            sb.append("\n");
            sb.append(" ".repeat(indent));
            sb.append("  additional info: ");
            sb.append(info.toString());
        }
        for (ExpressionHReport child : r.children) {
            sb.append(" ".repeat(indent));
            sb.append(String.format("%s", ExpressionHReport.toString(child, indent+2)));
        }
        return sb.toString();
    }

    // TODO : quick fix, to move elsewhere
    static String exprToPrettyString (Expression expr) {
        if (expr instanceof TripleExpr te)
            return tripleExprToPrettyString(te);
        if (expr instanceof ShapeExpr se)
            return shapeExprToPrettySting(se);
        return expr.toString();
    }

    // TODO : quick fix, to move elsewhere
    static String shapeExprToPrettySting (ShapeExpr expr) {
        if (expr instanceof ShapeExprRef ref)
            return "@" + ref.getLabel();
        if (expr instanceof ShapeNot shapeNot)
            return "NOT " + shapeExprToPrettySting(shapeNot.getShapeExpr());
        if (expr instanceof ShapeCollection shapeCollection)
            return "(( " + shapeExprToPrettySting(shapeCollection.getShapeExpr()) + " ))" + shapeCollection.boundsString();
        if (expr instanceof ShapeAnd shapeAnd)
            return String.join(" AND ",
                    shapeAnd.getShapeExprs().stream()
                            .map(ExpressionHReport::shapeExprToPrettySting)
                            .toArray(String[]::new));

        if (expr instanceof ShapeOr shapeOr)
            return String.join(" AND ",
                    shapeOr.getShapeExprs().stream()
                            .map(ExpressionHReport::shapeExprToPrettySting)
                            .toArray(String[]::new));

        if (expr instanceof Shape shape) {
            String extras = shape.getExtras().isEmpty()
                    ? ""
                    : "EXTRA " + shape.getExtras();
            String closed = shape.isClosed() ? "CLOSED" : "";
            String xtends = shape.getExtends().isEmpty()
                    ? ""
                    : "EXTEND " + shape.getExtends();
            return String.format("Shape %s %s %s %s", xtends, closed, extras,
                    tripleExprToPrettyString(shape.getTripleExpr()));
        }
        if (expr instanceof NodeConstraint nc) {
            if (nc.getComponents().isEmpty())
                return "NodeConstraint[ . ]";
            String s = nc.toString();
            return s.substring(0, s.length() - "/NodeConstraint".length());
        }
        return expr.toString();
    }

    // TODO : quick fix, to move elsewhere
    static String tripleExprToPrettyString (TripleExpr expr) {
        //return PrettyPrinter.asPrettyString(expr);
        if (expr instanceof TripleConstraint tripleConstr) {
            String p = tripleConstr.getPredicate().toString();
            String pp = p.substring(p.lastIndexOf('/')+1);
            return String.format("ex:%s %s", pp, shapeExprToPrettySting(tripleConstr.getValueExpr()));
        }
        if (expr instanceof EachOf eachOf)
            return String.join (" ; ",
                    eachOf.getTripleExprs().stream()
                            .map(ExpressionHReport::tripleExprToPrettyString)
                            .toArray(String[]::new));
        if (expr instanceof OneOf oneOf)
            return String.join (" | ",
                    oneOf.getTripleExprs().stream()
                            .map(ExpressionHReport::tripleExprToPrettyString)
                            .toArray(String[]::new));
        if (expr instanceof TripleExprCardinality card)
            return String.format("%s [%d, %d]",
                    card.getSubExpr(),
                    card.getCardinality().min,
                    card.getCardinality().max);
        return expr.toString();
    }

    public Node getNode() {
        return node;
    }

    public Expression getExpr() {
        return expr;
    }

    public Set<Triple> getSubNeigh() {
        return subNeigh;
    }

    public void setStatus(ShexStatus status) {
        this.status = status;
    }
}
