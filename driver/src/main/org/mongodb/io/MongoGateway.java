/*
 * Copyright (c) 2008 - 2013 10gen, Inc. <http://10gen.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.mongodb.io;

import org.bson.io.BasicInputBuffer;
import org.bson.io.InputBuffer;
import org.mongodb.MongoClientOptions;
import org.mongodb.ServerAddress;
import org.mongodb.protocol.MongoReplyHeader;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

import static org.mongodb.protocol.MongoReplyHeader.REPLY_HEADER_LENGTH;


/**
 * A gateway for the MongoDB wire protocol.
 * <p/>
 * Note: This class is not part of the public API.  It may break binary compatibility even in minor releases.
 */
public abstract class MongoGateway {
    private final ServerAddress address;
    private final BufferPool<ByteBuffer> pool;

    public static MongoGateway create(final ServerAddress address, final BufferPool<ByteBuffer> pool,
                                      final MongoClientOptions options) {
        if (options.isSSLEnabled()) {
            return new MongoSocketGateway(address, pool, SSLSocketFactory.getDefault());
        }
        else if (System.getProperty("org.mongodb.useSocket", "false").equals("true")) {
            return new MongoSocketGateway(address, pool, SocketFactory.getDefault());
        }
        else {
            return new MongoSocketChannelGateway(address, pool);
        }
    }

    public ServerAddress getAddress() {
        return address;
    }


    public void sendMessage(final ChannelAwareOutputBuffer buffer) {
        ensureOpen();
        sendOneWayMessage(buffer);
    }

    protected abstract void sendOneWayMessage(final ChannelAwareOutputBuffer buffer);


    public ResponseBuffers sendAndReceiveMessage(final ChannelAwareOutputBuffer buffer) {
        ensureOpen();
        long start = System.nanoTime();
        sendOneWayMessage(buffer);
        return receiveMessage(start);
    }

    public abstract void close();

    protected MongoGateway(final ServerAddress address, final BufferPool<ByteBuffer> pool) {
        this.address = address;
        this.pool = pool;
    }

    protected abstract void ensureOpen();

    protected abstract void fillAndFlipBuffer(final ByteBuffer buffer);

    protected void handleIOException(final IOException e) {
        if (e instanceof SocketTimeoutException) {
            throw new MongoSocketReadTimeoutException("Exception receiving message", address, (SocketTimeoutException) e);
        }
        else if (e instanceof InterruptedIOException) {
            throw new MongoSocketInterruptedReadException("Exception receiving message", address, (InterruptedIOException) e);
        }
        else {
            throw new MongoSocketReadException("Exception receiving message", address, e);
        }
    }

    private ResponseBuffers receiveMessage(final long start) {
        ByteBuffer headerByteBuffer = pool.get(REPLY_HEADER_LENGTH);

        final MongoReplyHeader replyHeader;
        try {
            fillAndFlipBuffer(headerByteBuffer);
            final InputBuffer headerInputBuffer = new BasicInputBuffer(headerByteBuffer);

            replyHeader = new MongoReplyHeader(headerInputBuffer);
        } finally {
            pool.done(headerByteBuffer);
        }

        PooledInputBuffer bodyInputBuffer = null;

        if (replyHeader.getNumberReturned() > 0) {
            ByteBuffer bodyByteBuffer = pool.get(replyHeader.getMessageLength() - REPLY_HEADER_LENGTH);
            fillAndFlipBuffer(bodyByteBuffer);

            bodyInputBuffer = new PooledInputBuffer(bodyByteBuffer, pool);
        }

        return new ResponseBuffers(replyHeader, bodyInputBuffer, System.nanoTime() - start);
    }
}