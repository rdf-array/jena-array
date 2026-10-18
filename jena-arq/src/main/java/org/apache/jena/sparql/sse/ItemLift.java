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

package org.apache.jena.sparql.sse;

import org.apache.jena.sparql.core.Node_SeqIndex;

import java.util.List;

import java.util.ArrayList;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.graph.NodeConst;
import org.apache.jena.sparql.sse.builders.BuilderLib;

/**
 * Lift and reverse.
 * <p>
 * All {@linkplain #liftItem} and {@link #lowerItem}
 * <p>
 * Compound - processes nodes that become compound items -- (qtriple)
 * <p>
 * Symbol - processes nodes by special names. TRUE, FALSE
 */
public class ItemLift {
    // ==== All conversions
    public static Item liftItem(Item item) {
        return ItemTransformer.transform(new LiftAll(), item);
    }

    public static Item lowerItem(Item item) {
        return ItemTransformer.transform(new LowerAll(), item);
    }

    // Create an item for a node, applying any conversions for compound nodes.
    private static Item nodeToItem(Node node) {
        return nodeToItem(node, -1, -1);
    }

    // Create an item for a node, applying any conversions for compound nodes.
    private static Item nodeToItem(Node node, int line, int column) {
        String symbol = fn_nodeToSymbol(node);
        if ( symbol != null )
            return Item.createSymbol(symbol, line, column);
        return lowerCompound(node, line, column);
    }

    // ==== Compound only

    public static Item liftCompound(Item item) {
        return ItemTransformer.transform(new LiftCompound(), item);
    }

    /** Reverse lift. */
    public static Item lowerCompound(Node node) {
        if ( node.isNodeTriple() || node.isNodeSeq() || node instanceof Node_SeqIndex ) {
            Item newItem = ItemLift.lowerCompound(node, -1, -1);
            return newItem;
        }
        return null;
    }

    /**
     * Reverse the lift transformation.
     */
    public static Item lowerCompound(Item item) {
        return ItemTransformer.transform(new LowerCompound(), item);
    }

    private static Item lowerCompound(Node node, int line, int column) {
        if ( node.isNodeTriple()) {
            Triple t = node.getTriple();
            Node s = t.getSubject();
            Node p = t.getPredicate();
            Node o = t.getObject();
            ItemList list = new ItemList();
            list.add(Tags.tagQTriple);
            list.add(nodeToItem(s, -1, -1));
            list.add(nodeToItem(p, -1, -1));
            list.add(nodeToItem(o, -1, -1));
            return Item.createList(list, line, column);
        }
        if ( node.isNodeSeq() ) {
            ItemList list = new ItemList();
            list.add(Tags.tagSeqTerm);
            for ( Node elt : node.getSeq() )
                list.add(nodeToItem(elt, -1, -1));
            return Item.createList(list, line, column);
        }
        if ( node instanceof Node_SeqIndex sx ) {
            ItemList list = new ItemList();
            list.add(Tags.tagSeqPos);
            list.add(nodeToItem(sx.getElement(), -1, -1));
            list.add(nodeToItem(sx.getIndex(), -1, -1));
            return Item.createList(list, line, column);
        }
        return Item.createNode(node);
    }

    // ==== Symbols only

    public static Item liftSymbol(Item item) {
        if ( ! item.isSymbol() )
            return item;
        String symbol = item.getSymbol();
        Node node = fn_symbolToNode(symbol);
        if ( node == null )
            return item;
        return Item.createNode(node, item.getLine(),item.getColumn());
    }

    public static Item lowerSymbol(Item item) {
        if ( ! item.isNode() )
            return item;
        Node node = item.getNode();
        String symbol = fn_nodeToSymbol(node);
        if ( symbol == null )
            return item;
        return Item.createSymbol(symbol, item.getLine(),item.getColumn());
    }

    public static Item lowerSymbol(Node node) {
        String symbol = fn_nodeToSymbol(node);
        if ( symbol == null )
            return null;
        return Item.createSymbol(symbol, -1, -1);
    }

    // ==== API

    // ---- Quoted triple specific

    private static boolean isValidQuotedTriple(Item item) {
        return item.isTagged(Tags.tagQTriple) && item.getList().size() == 4;
    }

    private static boolean isValidQuotedTriple(ItemList list) {
        return list.isTagged(Tags.tagQTriple) && list.size() == 4;
    }

    // ---- Sequence terms: (seqterm e1 e2 ...) and sequence position patterns (seqpos element index)

    private static boolean isValidSeqTerm(ItemList list) {
        return list.isTagged(Tags.tagSeqTerm);
    }

    private static boolean isValidSeqPos(ItemList list) {
        return list.isTagged(Tags.tagSeqPos) && list.size() == 3;
    }

    private static boolean isCompound(ItemList list) {
        return isValidQuotedTriple(list) || isValidSeqTerm(list) || isValidSeqPos(list);
    }

    // Entry to recursion.
    private static Item liftQuotedTriple(ItemList list, int line, int column) {
        Node n = liftQuotedTripleToNode(list);
        return Item.createNode(n, line, column);
    }

    private static Item liftCompound(ItemList list, int line, int column) {
        Node n = liftCompoundToNode(list);
        return Item.createNode(n, line, column);
    }

    private static Node liftQuotedTripleToNode(Item item) {
        return liftCompoundToNode(item);
    }

    // A node, or a compound (quoted triple, sequence term, sequence position pattern).
    private static Node liftCompoundToNode(Item item) {
        if ( item.isNode() )
            return item.getNode();
        if ( item.isSymbol() ) {
            Node n = fn_symbolToNode(item.getSymbol());
            if ( n != null )
                return n;
            if ( "ANY".equals(item.getSymbol()) )
                return Node.ANY;
        }
        if ( ! item.isList() || ! isCompound(item.getList()) )
            throw new SSE_Exception("Not valid for a compound term (quoted triple, sequence): "+item.toString());
        return liftCompoundToNode(item.getList());
    }

    private static Node liftQuotedTripleToNode(ItemList list) {
        if ( list.isEmpty() )
            broken(list, "Not a quoted triple (empty list)");
        if ( ! isValidQuotedTriple(list) )
            broken(list, "Not valid for a quoted triple: "+list);
        return buildQuotedTriple(list);
    }

    private static Node liftCompoundToNode(ItemList list) {
        if ( list.isEmpty() )
            broken(list, "Not a compound term (empty list)");
        if ( isValidQuotedTriple(list) )
            return buildQuotedTriple(list);
        if ( isValidSeqTerm(list) )
            return buildSeqTerm(list);
        if ( isValidSeqPos(list) )
            return buildSeqPos(list);
        broken(list, "Not valid for a compound term: "+list);
        return null;
    }

    private static Node buildQuotedTriple(ItemList list) {
        // Recurses
        Node s = liftCompoundToNode(list.get(1));
        Node p = liftCompoundToNode(list.get(2));
        Node o = liftCompoundToNode(list.get(3));
        Triple triple = Triple.create(s, p, o);
        return NodeFactory.createTripleNode(triple);
    }

    private static Node buildSeqTerm(ItemList list) {
        List<Node> elts = new ArrayList<>(list.size()-1);
        for ( int i = 1 ; i < list.size() ; i++ )
            elts.add(liftCompoundToNode(list.get(i)));
        return NodeFactory.createSeqNode(elts);
    }

    private static Node buildSeqPos(ItemList list) {
        Node elt = liftCompoundToNode(list.get(1));
        Node idx = liftCompoundToNode(list.get(2));
        return Node_SeqIndex.create(elt, idx);
    }

    // ---- Symbols

    // No default values support.
    private static Node fn_symbolToNode(String symbol) {
        switch(symbol) {
            case Tags.tagTrue:  return NodeConst.TRUE;
            case Tags.tagFalse: return NodeConst.FALSE;
            // StatsMatcher uses symbol ANY.
            //case Tags.tagANY:   return Node.ANY;
        }
        return null;
    }

    // No default values support.
    private static String fn_nodeToSymbol(Node node) {
        if ( NodeConst.TRUE.equals(node) )
            return Tags.tagTrue;
        if ( NodeConst.FALSE.equals(node) )
            return Tags.tagFalse;
        if ( Node.ANY.equals(node) )
            return "ANY";
        return null;
    }

    // ---- Item Transformers

    private static class LiftAll extends ItemTransformBase {
        @Override
        public Item transform(Item item, ItemList list) {
            if ( isCompound(list) )
                return ItemLift.liftCompound(list, item.getLine(),item.getColumn());
            return super.transform(item, list);
        }

        @Override
        public Item transform(Item item, String symbol) {
            Node node = fn_symbolToNode(symbol);
            if ( node != null )
                return Item.createNode(node, item.getLine(),item.getColumn());
            return super.transform(item, symbol);
        }
    }

    private static class LowerAll extends ItemTransformBase {
        @Override
        public Item transform(Item item, Node node) {
            Item item2 = nodeToItem(node, item.getLine(), item.getColumn());
            if (item2 != null )
                return item2;
            if ( node.isNodeTriple() || node.isNodeSeq() || node instanceof Node_SeqIndex )
                return ItemLift.lowerCompound(node, item.getLine(), item.getColumn());
            return super.transform(item, node);
        }
    }

    private static class LiftCompound extends ItemTransformBase {
        @Override
        public Item transform(Item item, ItemList list) {
            if ( isCompound(list) )
                return ItemLift.liftCompound(list, item.getLine(),item.getColumn());
            return super.transform(item, list);
        }
    }

    private static class LowerCompound extends ItemTransformBase {
        @Override
        public Item transform(Item item, Node node) {
            if ( node.isNodeTriple() || node.isNodeSeq() || node instanceof Node_SeqIndex ) {
                Item newItem = ItemLift.nodeToItem(node, item.getLine(),item.getColumn());
                return newItem;
            }
            else
                return super.transform(item, node);
        }
    }

//    // -- Symbols for constants to nodes
//    private static class SymToNode extends ItemTransformBase {
//        @Override
//        public Item transform(Item item, String symbol) {
//            Node node = fn_symbolToNode(symbol);
//            if ( node != null )
//                return Item.createNode(node, item.getLine(), item.getColumn());
//            return super.transform(item, node);
//        }
//    }
//
//    private static class NodeToSym extends ItemTransformBase {
//        @Override
//        public Item transform(Item item, Node node) {
//            String symbol = fn_nodeToSymbol(node);
//            if ( symbol != null )
//                return Item.createSymbol(symbol, item.getLine(), item.getColumn());
//            return super.transform(item, node);
//        }
//    }

    private static void broken(ItemLocation location, String msg) {
        BuilderLib.broken(location, msg);
    }

}
