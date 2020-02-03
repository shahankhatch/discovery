/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ethereum.beacon.discovery.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;

/**
 * UDP Packet -> BytesValue converter with default Netty interface
 */
public class DatagramToBytesValue extends MessageToMessageDecoder<DatagramPacket> {

  Logger logger = LogManager.getLogger();

  @Override
  protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out)
      throws Exception {
    ByteBuf buf = msg.content();
    byte[] data = new byte[buf.readableBytes()];
    buf.readBytes(data);

    out.add(Bytes.concatenate(
        Bytes.wrap(msg.sender().getAddress().getAddress()),
        Bytes.ofUnsignedInt(msg.sender().getPort()),
        Bytes.wrap(data)));

  }
}
