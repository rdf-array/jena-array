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

import org.apache.jena.graph.Node;
import org.apache.jena.shex.ShexException;
import org.apache.jena.shex.sys.ShexLib;

import java.util.Objects;

public class ValueSetItem {
    String iriStr;
    String langStr;
    Node literal;
    boolean isStem;

    public ValueSetItem(String iriStr, String lang, Node literal, boolean isStem) {
        this.iriStr = iriStr;
        // [shex] In ValueSetRange :: ( item.langStr.isEmpty() ) ? "*" : item.langStr;
        this.langStr = lang;
        this.literal = literal;
        this.isStem = isStem;
        if ( literal != null && ! literal.isLiteral() )
            throw new ShexException("Not literal: "+ShexLib.displayStr(literal));
    }

    /** The IRI (or IRI stem) of the item, or null. */
    public String getIriStr() { return iriStr; }

    /** The language tag (or language stem) of the item, or null. */
    public String getLangStr() { return langStr; }

    /** The literal (or literal stem) of the item, or null. */
    public Node getLiteral() { return literal; }

    public boolean isStem() {
        return isStem;
    }

    public boolean isEmpty() {
        return iriStr == null && langStr == null && literal == null;
    }

    @Override
    public String toString() {
        return PrettyPrinter.asPrettyString(this);
    }

    @Override
    public int hashCode() {
        return Objects.hash(iriStr, isStem, langStr, literal);
    }

    @Override
    public boolean equals(Object obj) {
        if ( this == obj )
            return true;
        if ( obj == null )
            return false;
        if ( getClass() != obj.getClass() )
            return false;
        ValueSetItem other = (ValueSetItem)obj;
        return Objects.equals(iriStr, other.iriStr) && isStem == other.isStem && Objects.equals(langStr, other.langStr)
               && Objects.equals(literal, other.literal);
    }
}
