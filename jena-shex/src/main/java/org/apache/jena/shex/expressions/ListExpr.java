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

import java.util.List;
import java.util.function.Consumer;

/**
 * The regular expression over the members of a {@link ListShape}: a
 * {@link ListMember} (one member matching a shape expression), a
 * {@link ListEachOf} (members in order) or a {@link ListOneOf} (a choice), each
 * with an optional cardinality (absent: exactly once).
 * <p>
 * ShExJ: {@code Member}, {@code ListEachOf}, {@code ListOneOf}, with {@code min}
 * and {@code max} as on triple expressions ({@code -1} unbounded).
 */
public abstract class ListExpr {

    // null: exactly once.
    private final Cardinality cardinality;

    protected ListExpr(Cardinality cardinality) {
        this.cardinality = cardinality;
    }

    /** The cardinality, or null for exactly once. */
    public Cardinality getCardinality() {
        return cardinality;
    }

    public boolean hasCardinality() {
        return cardinality != null;
    }

    /** Least number of repetitions. */
    public int min() {
        return cardinality == null ? 1 : cardinality.min;
    }

    /** Greatest number of repetitions; {@link Cardinality#UNBOUNDED} for no limit. */
    public int max() {
        return cardinality == null ? 1 : cardinality.max;
    }

    /** This expression with the cardinality replaced. */
    public abstract ListExpr withCardinality(Cardinality cardinality);

    /**
     * Apply {@code cardinality} to this expression: set it when the expression has
     * none, else wrap the expression in a {@link ListEachOf} of one, as
     * {@code ( x {2} ) {3}} does.
     */
    public ListExpr applyCardinality(Cardinality cardinality) {
        if ( cardinality == null )
            return this;
        if ( !hasCardinality() )
            return withCardinality(cardinality);
        return ListEachOf.create(List.of(this), cardinality);
    }

    /** Every shape expression in the expression, in order, depth first. */
    public abstract void forEachShapeExpr(Consumer<ShapeExpr> action);

    @Override
    public abstract int hashCode();

    @Override
    public abstract boolean equals(Object obj);

    @Override
    public String toString() {
        return PrettyPrinter.asPrettyString(this);
    }

    protected String cardinalityString() {
        return cardinality == null ? "" : " " + PrettyPrinter.asPrettyString(cardinality);
    }
}
