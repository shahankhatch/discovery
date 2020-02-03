/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ethereum.beacon.discovery.message.handler;

import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ethereum.beacon.discovery.message.NodesMessage;
import org.ethereum.beacon.discovery.pipeline.info.FindNodeRequestInfo;
import org.ethereum.beacon.discovery.pipeline.info.RequestInfo;
import org.ethereum.beacon.discovery.pipeline.info.RequestInfoFactory;
import org.ethereum.beacon.discovery.schema.NodeRecord;
import org.ethereum.beacon.discovery.schema.NodeRecordInfo;
import org.ethereum.beacon.discovery.schema.NodeSession;
import org.ethereum.beacon.discovery.task.TaskOptions;
import org.ethereum.beacon.discovery.task.TaskStatus;
import org.ethereum.beacon.discovery.task.TaskType;

public class NodesHandler implements MessageHandler<NodesMessage> {

  private static final Logger logger = LogManager.getLogger(FindNodeHandler.class);
  private final NodeRecord homeNode;

  public NodesHandler(NodeRecord homeNode) {
    this.homeNode = homeNode;
  }

  @Override
  public void handle(NodesMessage message, NodeSession session) {
    // NODES total count handling
    Optional<RequestInfo> requestInfoOpt = session.getRequestId(message.getRequestId());
    if (!requestInfoOpt.isPresent()) {
      throw new RuntimeException(
          String.format(
              "Request #%s not found in session %s when handling message %s",
              message.getRequestId(), session, message));
    }
    RequestInfo requestInfo1 = RequestInfoFactory
        .create(requestInfoOpt.get().getTaskType(), requestInfoOpt.get().getRequestId(),
            new TaskOptions(false, 0), requestInfoOpt.get().getFuture());
    FindNodeRequestInfo requestInfo = (FindNodeRequestInfo) requestInfo1;
    int newNodesCount =
        requestInfo.getRemainingNodes() == null
            ? message.getTotal() - 1
            : requestInfo.getRemainingNodes() - 1;
    if (newNodesCount == 0) {
//      session.clearRequestId(message.getRequestId(), TaskType.FINDNODE);
    } else {
      session.updateRequestInfo(
          message.getRequestId(),
          new FindNodeRequestInfo(
              TaskStatus.IN_PROCESS,
              message.getRequestId(),
              requestInfo.getFuture(),
              requestInfo.getDistance(),
              newNodesCount));
    }

    // Parse node records
    logger.trace(
        () ->
            String.format(
                "On %s, Received %s node records in session %s. Total buckets expected: %s. Nodes are: %s",
                this.homeNode,
                message.getNodeRecordsSize(), session, message.getTotal(), message.getNodeRecords()));
    message
        .getNodeRecords()
        .forEach(
            nodeRecordV5 -> {
//              nodeRecordV5.verify();
//              if (session.getNodeTable().getNode(nodeRecordV5.getNodeId()).isEmpty() && !session
//                  .getHomeNodeId().equals(nodeRecordV5.getNodeId())) {
                NodeRecordInfo nodeRecordInfo = NodeRecordInfo.createDefault(nodeRecordV5);
                logger.debug(() -> String
                    .format("On %s, NodesHandler, handling distinct received node:%s ",
                        this.homeNode.getPort(), nodeRecordV5));
                session.putRecordInBucket(nodeRecordInfo);
                session.getNodeTable().save(nodeRecordInfo);
//              }
            });
  }
}
