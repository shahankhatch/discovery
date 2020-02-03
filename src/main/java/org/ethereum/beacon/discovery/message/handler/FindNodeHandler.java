/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ethereum.beacon.discovery.message.handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ethereum.beacon.discovery.message.DiscoveryV5Message;
import org.ethereum.beacon.discovery.message.FindNodeMessage;
import org.ethereum.beacon.discovery.message.NodesMessage;
import org.ethereum.beacon.discovery.packet.MessagePacket;
import org.ethereum.beacon.discovery.schema.NodeRecord;
import org.ethereum.beacon.discovery.schema.NodeRecordInfo;
import org.ethereum.beacon.discovery.schema.NodeSession;
import org.ethereum.beacon.discovery.storage.NodeBucket;

public class FindNodeHandler implements MessageHandler<FindNodeMessage> {

  private static final Logger logger = LogManager.getLogger(FindNodeHandler.class);
  /**
   * The maximum size of any packet is 1280 bytes. Implementations should not generate or process
   * packets larger than this size. As per specification the maximum size of an ENR is 300 bytes. A
   * NODES message containing all FINDNODE response records would be at least 4800 bytes, not
   * including additional data such as the header. To stay below the size limit, NODES responses are
   * sent as multiple messages and specify the total number of responses in the message. 4х300 =
   * 1200 and we always have 80 bytes for everything else.
   */
  private static final int MAX_NODES_PER_MESSAGE = 4;
  private final NodeRecord homeNode;

  public FindNodeHandler(NodeRecord homeNode) {
    this.homeNode = homeNode;
  }

  @Override
  public void handle(FindNodeMessage message, NodeSession session) {
    NodeBucket nb = new NodeBucket();
    for (int i = 0; i <= message.getDistance(); i++) {
      session.getBucket(i).ifPresent(nodeBucket -> nb.putAll(nodeBucket.getNodeRecords()));
    }

    List<NodeRecordInfo> bucketRecords = nb.getNodeRecords()
        .subList(0, Math.min(nb.getNodeRecords().size(), 4));

    logger.trace(
        () ->
            String.format(
                "On %s, Sending %s nodes in reply to request with distance %s in session %s: %s",
                this.homeNode,
                bucketRecords.size(), message.getDistance(), session, bucketRecords));

    session.sendOutgoing(
        MessagePacket.create(
            session.getHomeNodeId(),
            session.getNodeRecord().getNodeId(),
            session.getAuthTag().get(),
            session.getInitiatorKey(),
            DiscoveryV5Message.from(
                new NodesMessage(
                    message.getRequestId(),
                    bucketRecords.size(),
                    () -> bucketRecords.stream().map(NodeRecordInfo::getNode).collect(
                        Collectors.toList()),
                    bucketRecords.size()))));
  }
}
