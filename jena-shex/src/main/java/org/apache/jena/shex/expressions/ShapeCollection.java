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

import java.util.Objects;

import org.apache.jena.shex.calc.TypedShapeExprVisitor;
import org.apache.jena.shex.calc.VoidShapeExprVisitor;

/**
 * Collection shape expression: {@code ((shapeExpr))[min, max]}.
 * <p>
 * Satisfied by an RDF sequence term with between {@code min} and {@code max}
 * elements (inclusive, {@code max} may be {@link Cardinality#UNBOUNDED}), each
 * element satisfying {@code shapeExpr}. A node that is not a sequence does not
 * satisfy a collection shape expression.
 * <p>
 * ShExJ: {@code {"type": "Collection", "shapeExpr": ..., "min": m, "max": n}}.
 * ShExR: {@code sx:Collection} with {@code sx:shapeExpr}, {@code sx:min}, {@code sx:max}.
 */
public class ShapeCollection extends ShapeExpr {

    public static ShapeExpr create(ShapeExpr shapeExpr, int min, int max) {
        return new ShapeCollection(shapeExpr, min, max);
    }

    /** A collection of any length: {@code ((shapeExpr))}. */
    public static ShapeExpr create(ShapeExpr shapeExpr) {
        return new ShapeCollection(shapeExpr, 0, Cardinality.UNBOUNDED);
    }

    private final ShapeExpr shapeExpr;
    private final int min;
    private final int max;

    private ShapeCollection(ShapeExpr shapeExpr, int min, int max) {
        super();
        Objects.requireNonNull(shapeExpr, "shapeExpr");
        if ( min < 0 || max < 0 )
            throw new IllegalArgumentException("Collection: negative bound: ["+min+", "+max+"]");
        if ( max < min )
            throw new IllegalArgumentException("Collection: max < min: ["+min+", "+max+"]");
        this.shapeExpr = shapeExpr;
        this.min = min;
        this.max = max;
    }

    /** The shape expression each element must satisfy. */
    public ShapeExpr getShapeExpr() {
        return shapeExpr;
    }

    /** Minimum number of elements. */
    public int getMin() {
        return min;
    }

    /** Maximum number of elements; {@link Cardinality#UNBOUNDED} for no limit. */
    public int getMax() {
        return max;
    }

    public boolean isUnbounded() {
        return max == Cardinality.UNBOUNDED;
    }

    /** True for the default {@code [0, *]}. */
    public boolean isAnyLength() {
        return min == 0 && isUnbounded();
    }

    /** Does a sequence of {@code size} elements fit the bounds? */
    public boolean acceptsSize(int size) {
        return size >= min && size <= max;
    }

    /** The bounds in ShExC syntax: {@code [m]}, {@code [m, n]}, {@code [m, *]} or "" for any length. */
    public String boundsString() {
        if ( isAnyLength() )
            return "";
        if ( min == max )
            return "["+min+"]";
        return "["+min+", "+(isUnbounded() ? "*" : Integer.toString(max))+"]";
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
        return Objects.hash(shapeExpr, min, max);
    }

    @Override
    public boolean equals(Object obj) {
        if ( this == obj )
            return true;
        if ( obj == null )
            return false;
        if ( getClass() != obj.getClass() )
            return false;
        ShapeCollection other = (ShapeCollection)obj;
        return min == other.min && max == other.max && Objects.equals(shapeExpr, other.shapeExpr);
    }
}
