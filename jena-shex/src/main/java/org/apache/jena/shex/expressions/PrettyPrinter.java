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

package org.apache.jena.shex.expressions;

import org.apache.jena.atlas.io.IndentedLineBuffer;
import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.graph.Node;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.riot.out.NodeFormatter;
import org.apache.jena.shex.ShapeDecl;
import org.apache.jena.shex.calc.VoidNodeConstraintComponentVisitor;
import org.apache.jena.shex.calc.VoidShapeExprVisitor;
import org.apache.jena.shex.calc.VoidTripleExprVisitor;
import org.apache.jena.shex.sys.ShexLib;
import org.apache.jena.shex.sys.SysShex;
import org.apache.jena.vocabulary.XSD;

import java.util.List;
import java.util.Set;

import static org.apache.jena.shex.sys.ShexLib.displayStr;

public class PrettyPrinter {

    public static String asPrettyString(ShapeExpr shapeExpr) {
        IndentedLineBuffer x = new IndentedLineBuffer();
        x.setFlatMode(true);
        shapeExpr.visit(new ExprPrinter(x, ShexLib.getNodeFmtAbbrev()));
        return x.asString();
    }

    public static String asPrettyString(TripleExpr tripleExpr) {
        IndentedLineBuffer x = new IndentedLineBuffer();
        x.setFlatMode(true);
        tripleExpr.visit(new ExprPrinter(x, ShexLib.getNodeFmtAbbrev()));
        return x.asString();
    }

    public static String asPrettyString(NodeConstraintComponent nodeConstrComp) {
        IndentedLineBuffer x = new IndentedLineBuffer();
        x.setFlatMode(true);
        nodeConstrComp.visit(new ExprPrinter(x, ShexLib.getNodeFmtAbbrev()));
        return x.asString();
    }

    public static String asPrettyString (Cardinality cardinality) {
        int min = cardinality.min;
        int max = cardinality.max;
        // Special syntax
        if ( min == 0 && max == Cardinality.UNBOUNDED )
            return "*";
        if ( min == 1 && max == Cardinality.UNBOUNDED )
            return "+";
        if ( min == 0 && max == 1 )
            return "?";
        // max == min => no comma.
        if ( max == min )
            return "{"+min+"}";
        // Max UNBOUNDED
        if ( max == Cardinality.UNBOUNDED )
            return "{"+min+",}";
        // General
        return "{"+min+","+max+"}";
    }

    public static String asPrettyString (ValueSetItem valueSetItem) {
        IndentedLineBuffer x = new IndentedLineBuffer();
        x.setFlatMode(true);
        print(valueSetItem, x, ShexLib.getNodeFmtAbbrev());
        return x.asString();
    }

    public static void print(ShapeExpr shapeExpr, IndentedWriter out, NodeFormatter nFmt) {
        shapeExpr.visit(new ExprPrinter(out, nFmt));
    }

    public static void print(ShapeDecl shapeDecl, IndentedWriter out, NodeFormatter nFmt) {

        out.printf("Shape: ");
        if (SysShex.startNode.equals(shapeDecl.getLabel()))
            out.print("START");
        else
            nFmt.format(out, shapeDecl.getLabel());
        out.println();
        out.incIndent();
        ShapeExpr shExpr = shapeDecl.getShapeExpr();
        if (shExpr != null)
            print(shExpr, out, nFmt);
        out.decIndent();
    }

    static class ExprPrinter implements VoidShapeExprVisitor, VoidTripleExprVisitor, VoidNodeConstraintComponentVisitor {

        final IndentedWriter out;
        final NodeFormatter nFmt;

        ExprPrinter(IndentedWriter out, NodeFormatter nFmt) {
            this.out = out;
            this.nFmt = nFmt;
        }

        @Override
        public void visit(ShapeAnd shapeAnd) {
            out.println("AND");
            visitShapeExprSubExprs(shapeAnd.getShapeExprs());
            out.println("/AND");
        }

        @Override
        public void visit(ShapeOr shapeOr) {
            out.println("OR");
            visitShapeExprSubExprs(shapeOr.getShapeExprs());
            out.println("/OR");
        }

        @Override
        public void visit(ShapeNot shapeNot) {
            out.print("NOT ");
            shapeNot.getShapeExpr().visit(this);
        }

        @Override
        public void visit(ShapeCollection shapeCollection) {
            out.print("Collection" + shapeCollection.boundsString() + " ");
            shapeCollection.getShapeExpr().visit(this);
        }

        @Override
        public void visit(ShapeExprRef shapeExprRef) {
            out.print("ShapeRef: ");
            out.print(ShexLib.displayStr(shapeExprRef.getLabel()));
            out.println();
        }

        @Override
        public void visit(ShapeExternal shapeExternal) {
            out.println("EXTERNAL");
        }

        @Override
        public void visit(Shape shape) {
            out.println("Shape");
            out.incIndent();
            List<? extends ShapeExpr> xtends = shape.getExtends();
            if (xtends != null && xtends.size() > 0) {
                out.println("EXTENDS");
                out.incIndent();
                visitShapeExprSubExprs(xtends);
                out.decIndent();
            }
            if (shape.isClosed())
                out.println("CLOSED");
            Set<Node> extras = shape.getExtras();
            if (extras != null && extras.size() > 0) {
                out.println("EXTRA");
                int idx = 0;
                out.incIndent();
                for (Node extra : extras) {
                    idx++;
                    out.printf("%d - ", idx);
                    out.println(extra);
                }
                out.decIndent();
            }
            out.println("TripleExpression");
            out.incIndent();
            if (shape.getTripleExpr() != null)
                shape.getTripleExpr().visit(this);
            else
                out.println("<none>");
            out.decIndent();
            out.decIndent();
        }

        @Override
        public void visit(TripleExprCardinality tripleExprCardinality) {
            String s = tripleExprCardinality.cardinalityString();
            if (s == null)
                s = tripleExprCardinality.getCardinality().toString();
            out.println("TripleExprCardinality");
            out.incIndent();
            out.println("Cardinality = " + s);
            tripleExprCardinality.getSubExpr().visit(this);
            out.decIndent();
            out.println("/TripleExprCardinality");
        }

        @Override
        public void visit(EachOf eachOf) {
            out.println("EachOf");
            visitTripleExprSubExprs(eachOf.getTripleExprs());
            out.println("/EachOf");
        }

        @Override
        public void visit(OneOf oneOf) {
            out.println("OneOf");
            visitTripleExprSubExprs(oneOf.getTripleExprs());
            out.println("/OneOf");
        }

        @Override
        public void visit(TripleExprEmpty tripleExprEmpty) {
            out.print("empty triple expression");
        }

        @Override
        public void visit(TripleExprRef tripleExprRef) {
            out.print("tripleExprRef: ");
            nFmt.format(out, tripleExprRef.getLabel());
            out.println();
        }

        @Override
        public void visit(TripleConstraint tripleConstraint) {
            out.print("TripleConstraint");
            if (tripleConstraint.getLabel() != null) {
                out.print(" $");
                nFmt.format(out, tripleConstraint.getLabel());
            }
            out.println(" {");
            out.incIndent();
            out.printf("predicate = ");
            if (tripleConstraint.isInverse())
                out.print("^");
            nFmt.format(out, tripleConstraint.getPredicate());
            out.println();
            tripleConstraint.getValueExpr().visit(this);
            out.decIndent();
            out.println("}");
        }

        @Override
        public void visit(NodeConstraint nodeConstraint) {
            out.print("NodeConstraint");
            out.incIndent();
            nodeConstraint.getComponents().forEach(it -> it.visit(this));
            out.decIndent();
            out.print("/NodeConstraint");
        }

        @Override
        public void visit(NodeKindConstraint nodeKindCstr) {
            out.write("NodeKind: " + nodeKindCstr.getNodeKind().toString());
        }

        @Override
        public void visit(DatatypeConstraint datatypeCstr) {
            String className = DatatypeConstraint.class.getSimpleName();
            Node datatype = datatypeCstr.getDatatype();
            String dtURI = datatypeCstr.getDatatypeURI();
            String x;
            if (datatype.isURI()) {
                if (dtURI.startsWith(XSD.getURI()))
                    x = "xsd:" + datatype.getLocalName();
                else
                    x = "<" + dtURI + ">";
            } else if (datatype.isBlank())
                x = "<_:" + datatype.getBlankNodeLabel() + ">";
            else
                x = displayStr(datatype);
            out.print(String.format("%s[%s]", className, x));
        }

        @Override
        public void visit(NumLengthConstraint numLengthCstr) {
            VoidNodeConstraintComponentVisitor.super.visit(numLengthCstr);
        }

        @Override
        public void visit(NumRangeConstraint numRangeCstr) {
            out.write("NumRange[" + numRangeCstr.getRangeKind().label() + " "
                    + NodeFmtLib.displayStr(numRangeCstr.getValue()) + "]");
        }

        @Override
        public void visit(StrRegexConstraint strRegexCstr) {
            String flagsStr = strRegexCstr.getFlagsStr();
            String patternString = strRegexCstr.getPatternString();
            if (flagsStr != null && !flagsStr.isEmpty())
                out.write("Pattern[" + patternString + "(" + flagsStr + ")]");
            else
                out.write("Pattern[" + patternString + "]");
        }

        @Override
        public void visit(StrLengthConstraint strLengthCstr) {
            out.write("StrLength[" + strLengthCstr.getLengthType().label() + " " + strLengthCstr.getLength() + "]");
        }

        @Override
        public void visit(ValueConstraint valueCstr) {

            if (valueCstr.getValueSetRanges().isEmpty()) {
                out.println("[ ]");
                return;
            }
            out.print("[");
            valueCstr.getValueSetRanges().forEach(valueSetRange -> {
                out.print(" ");
                print(valueSetRange.item, out, nFmt);
                if (!valueSetRange.getExclusions().isEmpty()) {
                    out.print(" -");
                    valueSetRange.getExclusions().forEach(ex -> {
                        out.print(" ");
                        print(ex, out, nFmt);
                    });
                }
            });
            out.println(" ]");
        }

        private void visitShapeExprSubExprs(List<? extends ShapeExpr> subExpressions) {
            int idx = 0;
            for (ShapeExpr shExpr : subExpressions) {
                idx++;
                out.printf("%d -", idx);
                out.incIndent(4);
                shExpr.visit(this);
                out.decIndent(4);
            }
        }

        private void visitTripleExprSubExprs(List<TripleExpr> subExpressions) {
            out.incIndent();
            int idx = 0;
            for (TripleExpr tExpr : subExpressions) {
                idx++;
                out.printf("%d - ", idx);
                tExpr.visit(this);
            }
            out.decIndent();
        }
    }

    public static void print(ValueSetItem valueSetItem, IndentedWriter out, NodeFormatter nFmt) {
        if (valueSetItem.iriStr != null) nFmt.formatURI(out, valueSetItem.iriStr);
        else if (valueSetItem.langStr != null) out.printf("@%s", valueSetItem.langStr);
        else if (valueSetItem.literal != null) nFmt.format(out, valueSetItem.literal);
        if (valueSetItem.isStem)
            out.print("~");

        /* this different implementation in from ValueSetItem.toString
        String str = "invalid";
        if ( iriStr != null ) str = "<"+iriStr+">";
        else if ( langStr != null ) str = "@"+langStr;
        else if ( literal != null ) str = ShexLib.strDatatype(literal);
        if ( isStem )
            str = str+"~";
        return str;
         */
    }



}
