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

/**
 * Handles raw BytesValue incoming data in {@link Field#INCOMING}
 */
public class IncomingDataPacker implements EnvelopeHandler {

  private static final Logger logger = LogManager.getLogger();
  private NodeRecord homeNode;

  public IncomingDataPacker(NodeRecord homeNode) {
    this.homeNode = homeNode;
  }

  @Override
  public void handle(Envelope envelope) {
    logger.trace(
        () ->
            String.format(
                "Envelope %s in IncomingDataPacker, checking requirements satisfaction",
                envelope.getId()));
    if (!HandlerUtil.requireField(Field.INCOMING, envelope)) {
      return;
    }
    logger.trace(
        () ->
            String.format(
                "Envelope %s in IncomingDataPacker, requirements are satisfied!",
                envelope.getId()));
    Bytes bytes = (Bytes) envelope.get(Field.INCOMING);
    envelope.put(Field.IP, bytes.slice(0, 4).copy());
    envelope.put(Field.PORT, bytes.slice(4, 4).copy());
    UnknownPacket unknownPacket = new UnknownPacket(bytes.slice(8));
    try {
      unknownPacket.verify();
      envelope.put(Field.PACKET_UNKNOWN, unknownPacket);
      logger.trace(
          () ->
              String.format("On %s, Incoming packet %s in envelope #%s", homeNode.getPort(), unknownPacket, envelope.getId()));
    } catch (Exception ex) {
      envelope.put(Field.BAD_PACKET, unknownPacket);
      envelope.put(Field.BAD_EXCEPTION, ex);
      envelope.put(Field.BAD_MESSAGE, "Incoming packet verification not passed");
      logger.trace(
          () ->
              String.format(
                  "Bad incoming packet %s in envelope #%s", unknownPacket, envelope.getId()));
    }
    envelope.remove(Field.INCOMING);
  }
}
