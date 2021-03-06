/**
 * Copyright 2016-2017 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
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
package org.reaktivity.nukleus.tcp.internal.streams;

import static java.net.StandardSocketOptions.SO_REUSEADDR;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.IntStream.concat;
import static java.util.stream.IntStream.generate;
import static java.util.stream.IntStream.of;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.rules.RuleChain.outerRule;
import static org.reaktivity.nukleus.tcp.internal.InternalSystemProperty.MAXIMUM_STREAMS_WITH_PENDING_WRITES;
import static org.reaktivity.nukleus.tcp.internal.InternalSystemProperty.WINDOW_SIZE;
import static org.reaktivity.nukleus.tcp.internal.streams.SocketChannelHelper.ALL;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.jboss.byteman.contrib.bmunit.BMScript;
import org.jboss.byteman.contrib.bmunit.BMUnitConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.kaazing.k3po.junit.annotation.Specification;
import org.kaazing.k3po.junit.rules.K3poRule;
import org.reaktivity.nukleus.tcp.internal.TcpCountersRule;
import org.reaktivity.nukleus.tcp.internal.streams.SocketChannelHelper.HandleWriteHelper;
import org.reaktivity.nukleus.tcp.internal.streams.SocketChannelHelper.ProcessDataHelper;
import org.reaktivity.reaktor.test.NukleusRule;

/**
 * Tests the handling of capacity exceeded conditions in the context of incomplete writes
 */
@RunWith(org.jboss.byteman.contrib.bmunit.BMUnitRunner.class)
@BMUnitConfig(loadDirectory="src/test/resources")
@BMScript(value="SocketChannelHelper.btm")
public class ClientPartialWriteLimitsIT
{
    private final K3poRule k3po = new K3poRule()
        .addScriptRoot("route", "org/reaktivity/specification/nukleus/tcp/control/route")
        .addScriptRoot("streams", "org/reaktivity/specification/nukleus/tcp/streams");

    private final TestRule timeout = new DisableOnDebug(new Timeout(5, SECONDS));

    private final NukleusRule nukleus = new NukleusRule("tcp")
        .directory("target/nukleus-itests")
        .commandBufferCapacity(1024)
        .responseBufferCapacity(1024)
        .counterValuesBufferCapacity(1024)
        .streams("tcp", "source#partition");

    private final TestRule properties = new SystemPropertiesRule()
        .setProperty(MAXIMUM_STREAMS_WITH_PENDING_WRITES.propertyName(), "1")
        .setProperty(WINDOW_SIZE.propertyName(), "15");

    private final TcpCountersRule counters = new TcpCountersRule()
        .directory("target/nukleus-itests")
        .commandBufferCapacity(1024)
        .responseBufferCapacity(1024)
        .counterValuesBufferCapacity(1024);

    @Rule
    public final TestRule chain = outerRule(SocketChannelHelper.RULE).around(properties)
                                  .around(nukleus).around(counters).around(k3po).around(timeout);

    @Test
    @Specification({
        "${route}/output/new/controller",
        "${streams}/client.sent.data.multiple.frames.partial.writes/client/source"
    })
    public void shouldWriteWhenMoreDataArrivesWhileAwaitingSocketWritableWithoutOverflowingSlot() throws Exception
    {
        ProcessDataHelper.fragmentWrites(IntStream.of(5, 6));
        AtomicBoolean allDataWritten = new AtomicBoolean(false);
        HandleWriteHelper.fragmentWrites(generate(() -> allDataWritten.get() ? ALL : 0));

        try (ServerSocketChannel server = ServerSocketChannel.open())
        {
            server.setOption(SO_REUSEADDR, true);
            server.bind(new InetSocketAddress("127.0.0.1", 0x1f90));

            k3po.start();
            k3po.awaitBarrier("ROUTED_OUTPUT");

            try (SocketChannel channel = server.accept())
            {
                k3po.notifyBarrier("ROUTED_INPUT");

                k3po.awaitBarrier("SECOND_WRITE_COMPLETED");
                allDataWritten.set(true);

                ByteBuffer buf = ByteBuffer.allocate(256);
                boolean closed = false;
                do
                {
                    int len = channel.read(buf);
                    if (len == -1)
                    {
                        closed = true;
                        break;
                    }
                } while (buf.position() < "client data 1".concat("client data 2").length());
                buf.flip();

                assertFalse(closed);
                assertEquals("client data 1".concat("client data 2"), UTF_8.decode(buf).toString());

                k3po.finish();
            }
        }

        assertEquals(1, counters.streams());
        assertEquals(1, counters.routes());
        assertEquals(0, counters.overflows());
    }

    @Test
    @Specification({
        "${route}/output/new/controller",
        "${streams}/client.sent.data.multiple.streams.second.was.reset/client/source"
    })
    public void shouldResetStreamsExceedingPartialWriteStreamsLimit() throws Exception
    {
        ProcessDataHelper.fragmentWrites(concat(of(1), generate(() -> 0))); // avoid spin write for first stream write
        AtomicBoolean resetReceived = new AtomicBoolean(false);
        HandleWriteHelper.fragmentWrites(generate(() -> resetReceived.get() ? ALL : 0));

        try (ServerSocketChannel server = ServerSocketChannel.open())
        {
            server.setOption(SO_REUSEADDR, true);
            server.bind(new InetSocketAddress("127.0.0.1", 0x1f90));

            k3po.start();
            k3po.awaitBarrier("ROUTED_OUTPUT");

            try (SocketChannel channel1 = server.accept();
                 SocketChannel channel2 = server.accept())
            {
                k3po.notifyBarrier("ROUTED_INPUT");

                k3po.awaitBarrier("SECOND_STREAM_RESET_RECEIVED");
                resetReceived.set(true);

                ByteBuffer buf = ByteBuffer.allocate(256);
                while (buf.position() < 13)
                {
                    int len = channel1.read(buf);
                    assert (len != -1);
                }
                buf.flip();
                assertEquals("client data 1", UTF_8.decode(buf).toString());

                buf.rewind();
                int len = 0;
                while (buf.position() < 13)
                {
                    len = channel2.read(buf);
                    if (len == -1)
                    {
                        break;
                    }
                }
                buf.flip();

                assertEquals(0, buf.remaining());
                assertEquals(-1, len);

                k3po.finish();
            }
        }

        assertEquals(1, counters.routes());
        assertEquals(1, counters.overflows());
        assertEquals(2, counters.streams());
    }
}
