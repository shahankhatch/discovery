/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ethereum.beacon.discovery.pipeline.handler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ethereum.beacon.discovery.message.DiscoveryMessage;
import org.ethereum.beacon.discovery.pipeline.Envelope;
import org.ethereum.beacon.discovery.pipeline.EnvelopeHandler;
import org.ethereum.beacon.discovery.pipeline.Field;
import org.ethereum.beacon.discovery.pipeline.HandlerUtil;
import org.ethereum.beacon.discovery.processor.DiscoveryV5MessageProcessor;
import org.ethereum.beacon.discovery.processor.MessageProcessor;
import org.ethereum.beacon.discovery.schema.NodeRecord;
import org.ethereum.beacon.discovery.schema.NodeRecordFactory;
import org.ethereum.beacon.discovery.schema.NodeSession;

public class MessageHandler implements EnvelopeHandler {
  private static final Logger logger = LogManager.getLogger(MessageHandler.class);
//  private final NodeRecord homeNode;
  private final MessageProcessor messageProcessor;

  public MessageHandler(NodeRecord homeNode, NodeRecordFactory nodeRecordFactory) {
//    this.homeNode = homeNode;
    this.messageProcessor =
        new MessageProcessor(new DiscoveryV5MessageProcessor(homeNode, nodeRecordFactory));
    ;
  }

  @Override
  public void handle(Envelope envelope) {
    logger.trace(
        () ->
            String.format(
                "Envelope %s in MessageHandler, checking requirements satisfaction",
                envelope.getId()));
    if (!HandlerUtil.requireField(Field.MESSAGE, envelope)) {
      return;
    }
    if (!HandlerUtil.requireField(Field.SESSION, envelope)) {
      return;
    }
    logger.trace(
        () ->
            String.format(
                "Envelope %s in MessageHandler, requirements are satisfied!", envelope.getId()));

    NodeSession session = (NodeSession) envelope.get(Field.SESSION);
    DiscoveryMessage message = (DiscoveryMessage) envelope.get(Field.MESSAGE);
    try {
      messageProcessor.handleIncoming(message, session);
    } catch (Exception ex) {
      logger.trace(
          () ->
              String.format(
                  "Failed to handle message %s in envelope #%s", message, envelope.getId()),
          ex);
      envelope.put(Field.BAD_MESSAGE, message);
      envelope.put(Field.BAD_EXCEPTION, ex);
      envelope.remove(Field.MESSAGE);
    }
  }
}
