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
 * An empty slot in a list, repeated per the cardinality: {@code undef},
 * {@code ( xsd:integer | undef ) *}. It is not a shape expression: it matches a slot and
 * nothing else, and no shape expression matches a slot, {@code .} included.
 * ShExJ: {@code { "type": "Undef", "min": m, "max": n }}. ShExR: {@code sx:Undef}.
 */
public class ListUndef extends ListExpr {

    public static ListUndef create(Cardinality cardinality) {
        return new ListUndef(cardinality);
    }

    private ListUndef(Cardinality cardinality) {
        super(cardinality);
    }

    @Override
    public ListExpr withCardinality(Cardinality cardinality) {
        return new ListUndef(cardinality);
    }

    @Override
    public void forEachShapeExpr(Consumer<ShapeExpr> action) {}

    @Override
    public int hashCode() {
        return Objects.hash(2, getCardinality());
    }

    @Override
    public boolean equals(Object obj) {
        if ( this == obj )
            return true;
        if ( obj == null || getClass() != obj.getClass() )
            return false;
        return Objects.equals(getCardinality(), ((ListUndef)obj).getCardinality());
    }
}
