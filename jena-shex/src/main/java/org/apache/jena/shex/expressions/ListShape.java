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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.jena.shex.calc.TypedShapeExprVisitor;
import org.apache.jena.shex.calc.VoidShapeExprVisitor;

/**
 * The LIST shape expression: {@code LIST ( regex )} or its shorthand {@code LIST card x}.
 * <p>
 * Satisfied by an RDF sequence term whose members, in order, match the regular
 * expression {@link #getExpression()}: a member is a shape expression, and
 * {@code ;}, {@code |}, groups and cardinalities combine members as they combine
 * triple constraints in a triple expression, except that {@code ;} is ordered here.
 * {@code LIST ( xsd:string ; xsd:integer )} is a pair, {@code LIST ( xsd:integer * )}
 * and {@code LIST * xsd:integer} any list of integers. A node that is not a sequence
 * does not satisfy a LIST shape expression. A slot of the sequence satisfies no member,
 * and only {@code undef} in the expression matches it: {@code LIST ( ( xsd:integer | undef ) * )}.
 * <p>
 * ShExJ: {@code { "type": "ListShape", "expression": listExpr }}.
 * ShExR: {@code sx:ListShape} with {@code sx:expression}.
 */
public class ListShape extends ShapeExpr {

    public static ListShape create(ListExpr expression) {
        return new ListShape(expression);
    }

    /** {@code LIST card x}, the same as {@code LIST ( x card )}. */
    public static ListShape create(ShapeExpr member, Cardinality cardinality) {
        return new ListShape(ListMember.create(member, cardinality));
    }

    private final ListExpr expression;

    private ListShape(ListExpr expression) {
        super();
        this.expression = Objects.requireNonNull(expression, "expression");
    }

    /** The regular expression over the members. */
    public ListExpr getExpression() {
        return expression;
    }

    /** Every shape expression a member may be matched against, depth first. */
    public List<ShapeExpr> memberShapeExprs() {
        List<ShapeExpr> acc = new ArrayList<>();
        expression.forEachShapeExpr(acc::add);
        return acc;
    }

    @Override
    public void visit(VoidShapeExprVisitor visitor) {
        visitor.visit(this);
    }

    @Override
    public <R,A> R visit(TypedShapeExprVisitor<R,A> visitor, A arg) {
        return visitor.visit(this, arg);
    }

    @Override
    public int hashCode() {
        return Objects.hash(7, expression);
    }

    @Override
    public boolean equals(Object obj) {
        if ( this == obj )
            return true;
        if ( obj == null || getClass() != obj.getClass() )
            return false;
        ListShape other = (ListShape)obj;
        return Objects.equals(expression, other.expression);
    }
}
