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
import java.util.function.Consumer;

/**
 * One member of a list, matching a shape expression, repeated per the cardinality:
 * {@code xsd:integer}, {@code @<Point> +}.
 * ShExJ: {@code { "type": "Member", "shapeExpr": …, "min": m, "max": n }}.
 */
public class ListMember extends ListExpr {

    public static ListMember create(ShapeExpr shapeExpr, Cardinality cardinality) {
        return new ListMember(shapeExpr, cardinality);
    }

    private final ShapeExpr shapeExpr;

    private ListMember(ShapeExpr shapeExpr, Cardinality cardinality) {
        super(cardinality);
        this.shapeExpr = Objects.requireNonNull(shapeExpr, "shapeExpr");
    }

    public ShapeExpr getShapeExpr() {
        return shapeExpr;
    }

    @Override
    public ListExpr withCardinality(Cardinality cardinality) {
        return new ListMember(shapeExpr, cardinality);
    }

    @Override
    public void forEachShapeExpr(Consumer<ShapeExpr> action) {
        action.accept(shapeExpr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(1, shapeExpr, getCardinality());
    }

    @Override
    public boolean equals(Object obj) {
        if ( this == obj )
            return true;
        if ( obj == null || getClass() != obj.getClass() )
            return false;
        ListMember other = (ListMember)obj;
        return Objects.equals(shapeExpr, other.shapeExpr) && Objects.equals(getCardinality(), other.getCardinality());
    }
}
