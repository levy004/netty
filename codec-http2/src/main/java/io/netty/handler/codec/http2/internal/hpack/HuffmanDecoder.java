/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

/*
 * Copyright 2014 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.netty.handler.codec.http2.internal.hpack;

import io.netty.util.internal.ThrowableUtil;
import io.netty.buffer.ByteBuf;
import io.netty.util.AsciiString;
import io.netty.util.ByteProcessor;
import io.netty.util.internal.ObjectUtil;

import java.io.IOException;

import static io.netty.handler.codec.http2.internal.hpack.HpackUtil.HUFFMAN_CODES;
import static io.netty.handler.codec.http2.internal.hpack.HpackUtil.HUFFMAN_CODE_LENGTHS;

final class HuffmanDecoder {

    private static final IOException EOS_DECODED = ThrowableUtil.unknownStackTrace(
            new IOException("HPACK - EOS Decoded"), HuffmanDecoder.class, "decode(...)");
    private static final IOException INVALID_PADDING = ThrowableUtil.unknownStackTrace(
            new IOException("HPACK - Invalid Padding"), HuffmanDecoder.class, "decode(...)");

    private static final Node ROOT = buildTree(HUFFMAN_CODES, HUFFMAN_CODE_LENGTHS);

    private final DecoderProcessor processor;

    HuffmanDecoder(int initialCapacity) {
        processor = new DecoderProcessor(initialCapacity);
    }

    /**
     * Decompresses the given Huffman coded string literal.
     *
     * @param buf the string literal to be decoded
     * @return the output stream for the compressed data
     * @throws IOException if an I/O error occurs. In particular, an <code>IOException</code> may be
     * thrown if the output stream has been closed.
     */
    public AsciiString decode(ByteBuf buf, int length) throws IOException {
        processor.reset();
        buf.forEachByte(buf.readerIndex(), length, processor);
        buf.skipBytes(length);
        return processor.end();
    }

    private static final class Node {

        private final int symbol;      // terminal nodes have a symbol
        private final int bits;        // number of bits matched by the node
        private final Node[] children; // internal nodes have children

        /**
         * Construct an internal node
         */
        Node() {
            symbol = 0;
            bits = 8;
            children = new Node[256];
        }

        /**
         * Construct a terminal node
         *
         * @param symbol the symbol the node represents
         * @param bits the number of bits matched by this node
         */
        Node(int symbol, int bits) {
            assert bits > 0 && bits <= 8;
            this.symbol = symbol;
            this.bits = bits;
            children = null;
        }

        private boolean isTerminal() {
            return children == null;
        }
    }

    private static Node buildTree(int[] codes, byte[] lengths) {
        Node root = new Node();
        for (int i = 0; i < codes.length; i++) {
            insert(root, i, codes[i], lengths[i]);
        }
        return root;
    }

    private static void insert(Node root, int symbol, int code, byte length) {
        // traverse tree using the most significant bytes of code
        Node current = root;
        while (length > 8) {
            if (current.isTerminal()) {
                throw new IllegalStateException("invalid Huffman code: prefix not unique");
            }
            length -= 8;
            int i = (code >>> length) & 0xFF;
            if (current.children[i] == null) {
                current.children[i] = new Node();
            }
            current = current.children[i];
        }

        Node terminal = new Node(symbol, length);
        int shift = 8 - length;
        int start = (code << shift) & 0xFF;
        int end = 1 << shift;
        for (int i = start; i < start + end; i++) {
            current.children[i] = terminal;
        }
    }

    private static final class DecoderProcessor implements ByteProcessor {
        private final int initialCapacity;
        private byte[] bytes;
        private int index;
        private Node node;
        private int current;
        private int currentBits;
        private int symbolBits;

        DecoderProcessor(int initialCapacity) {
            this.initialCapacity = ObjectUtil.checkPositive(initialCapacity, "initialCapacity");
        }

        void reset() {
            node = ROOT;
            current = 0;
            currentBits = 0;
            symbolBits = 0;
            bytes = new byte[initialCapacity];
            index = 0;
        }

        /*
         * The idea here is to consume whole bytes at a time rather than individual bits. node
         * represents the Huffman tree, with all bit patterns denormalized as 256 children. Each
         * child represents the last 8 bits of the huffman code. The parents of each child each
         * represent the successive 8 bit chunks that lead up to the last most part. 8 bit bytes
         * from buf are used to traverse these tree until a terminal node is found.
         *
         * current is a bit buffer. The low order bits represent how much of the huffman code has
         * not been used to traverse the tree. Thus, the high order bits are just garbage.
         * currentBits represents how many of the low order bits of current are actually valid.
         * currentBits will vary between 0 and 15.
         *
         * symbolBits is the number of bits of the the symbol being decoded, *including* all those
         * of the parent nodes. symbolBits tells how far down the tree we are. For example, when
         * decoding the invalid sequence {0xff, 0xff}, currentBits will be 0, but symbolBits will be
         * 16. This is used to know if buf ended early (before consuming a whole symbol) or if
         * there is too much padding.
         */
        @Override
        public boolean process(byte value) throws IOException {
            current = (current << 8) | (value & 0xFF);
            currentBits += 8;
            symbolBits += 8;
            // While there are unconsumed bits in current, keep consuming symbols.
            do {
                node = node.children[(current >>> (currentBits - 8)) & 0xFF];
                currentBits -= node.bits;
                if (node.isTerminal()) {
                    if (node.symbol == HpackUtil.HUFFMAN_EOS) {
                        throw EOS_DECODED;
                    }
                    append(node.symbol);
                    node = ROOT;
                    // Upon consuming a whole symbol, reset the symbol bits to the number of bits
                    // left over in the byte.
                    symbolBits = currentBits;
                }
            } while (currentBits >= 8);
            return true;
        }

        AsciiString end() throws IOException {
            /*
             * We have consumed all the bytes in buf, but haven't consumed all the symbols. We may be on
             * a partial symbol, so consume until there is nothing left. This will loop at most 2 times.
             */
            while (currentBits > 0) {
                node = node.children[(current << (8 - currentBits)) & 0xFF];
                if (node.isTerminal() && node.bits <= currentBits) {
                    if (node.symbol == HpackUtil.HUFFMAN_EOS) {
                        throw EOS_DECODED;
                    }
                    currentBits -= node.bits;
                    append(node.symbol);
                    node = ROOT;
                    symbolBits = currentBits;
                } else {
                    break;
                }
            }

            // Section 5.2. String Literal Representation
            // A padding strictly longer than 7 bits MUST be treated as a decoding error.
            // Padding not corresponding to the most significant bits of the code
            // for the EOS symbol (0xFF) MUST be treated as a decoding error.
            int mask = (1 << symbolBits) - 1;
            if (symbolBits > 7 || (current & mask) != mask) {
                throw INVALID_PADDING;
            }

            return new AsciiString(bytes, 0, index, false);
        }

        private void append(int i) {
            try {
                bytes[index] = (byte) i;
            } catch (IndexOutOfBoundsException ignore) {
                // Always just expand by INITIAL_SIZE
                byte[] newBytes = new byte[bytes.length + initialCapacity];
                System.arraycopy(bytes, 0, newBytes, 0, bytes.length);
                bytes = newBytes;
                bytes[index] = (byte) i;
            }
            index++;
        }
    }
}
