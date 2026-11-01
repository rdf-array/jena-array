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
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Members in order: {@code xsd:string ; xsd:integer}. Unlike a triple expression's
 * {@code EachOf}, this one is ordered, hence the distinct name.
 * ShExJ: {@code { "type": "ListEachOf", "expressions": [ … ], "min": m, "max": n }}.
 */
public class ListEachOf extends ListExpr {

    public static ListEachOf create(List<ListExpr> expressions, Cardinality cardinality) {
        return new ListEachOf(expressions, cardinality);
    }

    private final List<ListExpr> expressions;

    private ListEachOf(List<ListExpr> expressions, Cardinality cardinality) {
        super(cardinality);
        this.expressions = List.copyOf(expressions);
    }

    public List<ListExpr> getExpressions() {
        return expressions;
    }

    @Override
    public ListExpr withCardinality(Cardinality cardinality) {
        return new ListEachOf(expressions, cardinality);
    }

    @Override
    public void forEachShapeExpr(Consumer<ShapeExpr> action) {
        expressions.forEach(e -> e.forEachShapeExpr(action));
    }

    @Override
    public int hashCode() {
        return Objects.hash(2, expressions, getCardinality());
    }

    @Override
    public boolean equals(Object obj) {
        if ( this == obj )
            return true;
        if ( obj == null || getClass() != obj.getClass() )
            return false;
        ListEachOf other = (ListEachOf)obj;
        return Objects.equals(expressions, other.expressions) && Objects.equals(getCardinality(), other.getCardinality());
    }
}
