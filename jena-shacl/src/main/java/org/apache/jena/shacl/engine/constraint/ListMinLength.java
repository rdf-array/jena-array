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
package org.apache.jena.shacl.engine.constraint;

import static org.apache.jena.shacl.lib.ShLib.displayStr;

import java.util.List;
import java.util.Objects;

import org.apache.jena.graph.Node;
import org.apache.jena.shacl.engine.ValidationContext;
import org.apache.jena.shacl.lib.ShLib;
import org.apache.jena.shacl.parser.ConstraintVisitor;
import org.apache.jena.shacl.validation.ReportItem;
import org.apache.jena.shacl.vocabulary.SHACL;

/**
 * sh:minListLength (SHACL 1.2). rdf-array: the value must be a SHACL list, which is a
 * sequence term, of at least the given number of members.
 */
public class ListMinLength extends ConstraintTerm {

    private final int minListLength;

    public ListMinLength(int minListLength) {
        this.minListLength = minListLength;
    }

    public int getMinListLength() {
        return minListLength;
    }

    @Override
    public ReportItem validate(ValidationContext vCxt, Node n) {
        List<Node> members = ShLib.listMembers(n);
        if ( members == null )
            return new ReportItem(toString()+": Not a list: "+displayStr(n), n);
        if ( members.size() >= minListLength )
            return null;
        return new ReportItem(toString()+": List too short ("+members.size()+" members): "+displayStr(n), n);
    }

    @Override
    public Node getComponent() {
        return SHACL.MinListLengthConstraintComponent;
    }

    @Override
    public void visit(ConstraintVisitor visitor){
        visitor.visit(this);
    }

    @Override
    public String toString() {
        return "MinListLength["+minListLength+"]";
    }

    @Override
    public int hashCode() {
        return Objects.hash(minListLength);
    }

    @Override
    public boolean equals(Object obj) {
        if ( this == obj )
            return true;
        if ( !(obj instanceof ListMinLength other) )
            return false;
        return minListLength == other.minListLength;
    }
}
