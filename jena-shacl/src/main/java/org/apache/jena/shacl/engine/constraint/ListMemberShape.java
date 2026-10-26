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

import java.util.ArrayList;
import java.util.List;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.shacl.engine.ValidationContext;
import org.apache.jena.shacl.lib.ShLib;
import org.apache.jena.shacl.parser.ConstraintVisitor;
import org.apache.jena.shacl.parser.Shape;
import org.apache.jena.shacl.validation.ReportItem;
import org.apache.jena.shacl.validation.ValidationProc;
import org.apache.jena.shacl.vocabulary.SHACL;
import org.apache.jena.sparql.util.FmtUtils;

/**
 * sh:memberShape (SHACL 1.2). rdf-array: the value must be a SHACL list, which is a
 * sequence term, and each of its members must conform to the shape. One result per
 * value node, naming the members that do not conform.
 */
public class ListMemberShape extends ConstraintOp1 {

    public ListMemberShape(Shape other) {
        super(other);
    }

    @Override
    public Node getComponent() {
        return SHACL.MemberShapeConstraintComponent;
    }

    @Override
    public ReportItem validate(ValidationContext vCxt, Graph data, Node node) {
        List<Node> members = ShLib.listMembers(node);
        if ( members == null )
            return new ReportItem(toString()+": Not a list: "+displayStr(node), node);
        List<Node> failed = new ArrayList<>();
        for ( Node member : members ) {
            ValidationContext vCxt2 = ValidationContext.create(vCxt);
            ValidationProc.execValidateShape(vCxt2, data, other, member);
            if ( ! vCxt2.generateReport().conforms() )
                failed.add(member);
        }
        if ( failed.isEmpty() )
            return null;
        StringBuilder msg = new StringBuilder(toString()).append(": Members not conforming:");
        failed.forEach(m -> msg.append(' ').append(displayStr(m)));
        return new ReportItem(msg.toString(), node);
    }

    @Override
    public void visit(ConstraintVisitor visitor){
        visitor.visit(this);
    }

    @Override
    public String toString() {
        return "MemberShape["+FmtUtils.stringForNode(other.getShapeNode())+"]";
    }
}
