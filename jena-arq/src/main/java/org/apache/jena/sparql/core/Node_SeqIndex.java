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

package org.apache.jena.sparql.core;

import java.util.Objects;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Node_Ext;
import org.apache.jena.shared.PrefixMapping;

/**
 * SPARQL pattern term for one position of a sequence: {@code ((element))[index]}.
 * <p>
 * It matches a data sequence when the element at the given position matches
 * {@code element}. The index is 1-based and is one of:
 * <ul>
 * <li>a constant {@code xsd:integer} - that position only;</li>
 * <li>a variable - every position, binding the variable to the position;</li>
 * <li>{@link Node#ANY} (written {@code [*]}) - every position.</li>
 * </ul>
 * This is a pattern-only term: it is never concrete and never appears in data.
 */
public class Node_SeqIndex extends Node_Ext<Node_SeqIndex.Spec> {

    public record Spec(Node element, Node index) {
        public Spec {
            Objects.requireNonNull(element, "element");
            Objects.requireNonNull(index, "index");
        }
    }

    /** Create a sequence position pattern. A null index means "any position". */
    public static Node_SeqIndex create(Node element, Node index) {
        return new Node_SeqIndex(new Spec(element, index == null ? Node.ANY : index));
    }

    private Node_SeqIndex(Spec spec) {
        super(spec);
    }

    /** The pattern for the element at the position. */
    public Node getElement() {
        return get().element();
    }

    /** The index pattern: an {@code xsd:integer} literal, a variable or {@link Node#ANY}. */
    public Node getIndex() {
        return get().index();
    }

    public boolean isAnyPosition() {
        return Node.ANY.equals(getIndex());
    }

    @Override
    public String toString(PrefixMapping pm) {
        return "((" + getElement().toString(pm) + "))[" + (isAnyPosition() ? "*" : getIndex().toString(pm)) + "]";
    }

    @Override
    public String toString() {
        return "((" + getElement() + "))[" + (isAnyPosition() ? "*" : getIndex().toString()) + "]";
    }
}
