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

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.jena.graph.Node;
import org.apache.jena.shacl.engine.ValidationContext;
import org.apache.jena.shacl.lib.ShLib;
import org.apache.jena.shacl.parser.ConstraintVisitor;
import org.apache.jena.shacl.validation.ReportItem;
import org.apache.jena.shacl.vocabulary.SHACL;

/**
 * sh:uniqueMembers (SHACL 1.2). rdf-array: the value must be a SHACL list, which is a
 * sequence term, with no member (by RDF term equality) occurring twice. {@code false}
 * constrains nothing.
 */
public class ListUniqueMembers extends ConstraintTerm {

    private final boolean uniqueMembers;

    public ListUniqueMembers(boolean uniqueMembers) {
        this.uniqueMembers = uniqueMembers;
    }

    public boolean isUniqueMembers() {
        return uniqueMembers;
    }

    @Override
    public ReportItem validate(ValidationContext vCxt, Node n) {
        if ( ! uniqueMembers )
            return null;
        List<Node> members = ShLib.listMembers(n);
        if ( members == null )
            return new ReportItem(toString()+": Not a list: "+displayStr(n), n);
        Set<Node> seen = new HashSet<>();
        for ( Node member : members ) {
            if ( ! seen.add(member) )
                return new ReportItem(toString()+": Repeated member "+displayStr(member)+" in "+displayStr(n), n);
        }
        return null;
    }

    @Override
    public Node getComponent() {
        return SHACL.UniqueMembersConstraintComponent;
    }

    @Override
    public void visit(ConstraintVisitor visitor){
        visitor.visit(this);
    }

    @Override
    public String toString() {
        return "UniqueMembers["+uniqueMembers+"]";
    }

    @Override
    public int hashCode() {
        return Objects.hash(uniqueMembers);
    }

    @Override
    public boolean equals(Object obj) {
        if ( this == obj )
            return true;
        if ( !(obj instanceof ListUniqueMembers other) )
            return false;
        return uniqueMembers == other.uniqueMembers;
    }
}
