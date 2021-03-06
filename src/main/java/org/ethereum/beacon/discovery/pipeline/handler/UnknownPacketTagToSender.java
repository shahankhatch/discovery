/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ethereum.beacon.discovery.pipeline.handler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.ethereum.beacon.discovery.packet.UnknownPacket;
import org.ethereum.beacon.discovery.pipeline.Envelope;
import org.ethereum.beacon.discovery.pipeline.EnvelopeHandler;
import org.ethereum.beacon.discovery.pipeline.Field;
import org.ethereum.beacon.discovery.pipeline.HandlerUtil;
import org.ethereum.beacon.discovery.schema.NodeRecord;
import org.javatuples.Pair;

/**
 * Assuming we have some unknown packet in {@link Field#PACKET_UNKNOWN}, resolves sender node id
 * using `tag` field of the packet. Next, puts it to the {@link Field#SESSION_LOOKUP} so sender
 * session could be resolved by another handler.
 */
public class UnknownPacketTagToSender implements EnvelopeHandler {
  private static final Logger logger = LogManager.getLogger(UnknownPacketTagToSender.class);
  private final Bytes homeNodeId;

  public UnknownPacketTagToSender(NodeRecord homeNodeRecord) {
    this.homeNodeId = homeNodeRecord.getNodeId();
  }

  @Override
  public void handle(Envelope envelope) {
    logger.trace(
        () ->
            String.format(
                "Envelope %s in UnknownPacketTagToSender, checking requirements satisfaction",
                envelope.getId()));
    if (!HandlerUtil.requireField(Field.PACKET_UNKNOWN, envelope)) {
      return;
    }
    logger.trace(
        () ->
            String.format(
                "Envelope %s in UnknownPacketTagToSender, requirements are satisfied!",
                envelope.getId()));

    if (!envelope.contains(Field.PACKET_UNKNOWN)) {
      return;
    }
    UnknownPacket unknownPacket = (UnknownPacket) envelope.get(Field.PACKET_UNKNOWN);
    Bytes fromNodeId = unknownPacket.getSourceNodeId(homeNodeId);
    envelope.put(
        Field.SESSION_LOOKUP,
        Pair.with(
            fromNodeId,
            (Runnable)
                () -> {
                  envelope.put(Field.BAD_PACKET, envelope.get(Field.PACKET_UNKNOWN));
                  envelope.put(
                      Field.BAD_EXCEPTION,
                      new RuntimeException(
                          String.format("Session couldn't be created for nodeId %s", fromNodeId)));
                  envelope.remove(Field.PACKET_UNKNOWN);
                }));
  }
}
