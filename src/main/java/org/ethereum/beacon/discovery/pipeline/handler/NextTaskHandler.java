/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ethereum.beacon.discovery.pipeline.handler;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.ethereum.beacon.discovery.packet.MessagePacket;
import org.ethereum.beacon.discovery.packet.RandomPacket;
import org.ethereum.beacon.discovery.pipeline.Envelope;
import org.ethereum.beacon.discovery.pipeline.EnvelopeHandler;
import org.ethereum.beacon.discovery.pipeline.Field;
import org.ethereum.beacon.discovery.pipeline.HandlerUtil;
import org.ethereum.beacon.discovery.pipeline.Pipeline;
import org.ethereum.beacon.discovery.pipeline.info.GeneralRequestInfo;
import org.ethereum.beacon.discovery.pipeline.info.RequestInfo;
import org.ethereum.beacon.discovery.scheduler.Scheduler;
import org.ethereum.beacon.discovery.schema.NodeRecord;
import org.ethereum.beacon.discovery.schema.NodeSession;
import org.ethereum.beacon.discovery.task.TaskMessageFactory;
import org.ethereum.beacon.discovery.task.TaskStatus;

/** Gets next request task in session and processes it */
public class NextTaskHandler implements EnvelopeHandler {
  private static final Logger logger = LogManager.getLogger(NextTaskHandler.class);
  private static final int DEFAULT_DELAY_MS = 1;
  private final NodeRecord nodeRecord;
  private final Pipeline outgoingPipeline;
  private final Scheduler scheduler;

  public NextTaskHandler(NodeRecord nodeRecord, Pipeline outgoingPipeline, Scheduler scheduler) {
    this.nodeRecord = nodeRecord;
    this.outgoingPipeline = outgoingPipeline;
    this.scheduler = scheduler;
  }

  public static void tryToSendAwaitTaskIfAny(
      NodeSession session, Pipeline outgoingPipeline, Scheduler scheduler) {
    if (session.getFirstAwaitRequestInfo().isPresent()) {
      Envelope dummy = new Envelope();
      dummy.put(Field.SESSION, session);
      scheduler.executeWithDelay(
          Duration.ofMillis(DEFAULT_DELAY_MS), () -> outgoingPipeline.push(dummy));
    }
  }

  @Override
  public void handle(Envelope envelope) {
    logger.trace(
        () ->
            String.format(
                "Envelope %s in NextTaskHandler, checking requirements satisfaction",
                envelope.getId()));
    if (!HandlerUtil.requireField(Field.SESSION, envelope)) {
      return;
    }
    logger.trace(
        () ->
            String.format(
                "Envelope %s in NextTaskHandler, requirements are satisfied!", envelope.getId()));

    NodeSession session = (NodeSession) envelope.get(Field.SESSION);
    Optional<RequestInfo> requestInfoOpt = session.getFirstAwaitRequestInfo();
    if (!requestInfoOpt.isPresent()) {
      logger.trace(() -> String.format("On %s, Envelope %s: no awaiting requests", nodeRecord, envelope.getId()));
      return;
    }

    RequestInfo requestInfo = requestInfoOpt.get();
    logger.trace(
        () ->
            String.format(
                "On %s, Envelope %s: processing awaiting request %s", nodeRecord, envelope.getId(), requestInfo));
    Bytes authTag = session.generateNonce();
    Bytes requestId = requestInfo.getRequestId();
    if (session.getStatus().equals(NodeSession.SessionStatus.INITIAL)) {
      RandomPacket randomPacket =
          RandomPacket.create(
              session.getHomeNodeId(),
              session.getNodeRecord().getNodeId(),
              authTag,
              new SecureRandom());
      session.setAuthTag(authTag);
      session.sendOutgoing(randomPacket);
      session.setStatus(NodeSession.SessionStatus.RANDOM_PACKET_SENT);
    } else if (session.getStatus().equals(NodeSession.SessionStatus.AUTHENTICATED)) {
      MessagePacket messagePacket =
          TaskMessageFactory.createPacketFromRequest(requestInfo, authTag, session);
      session.sendOutgoing(messagePacket);
      RequestInfo sentRequestInfo =
          new GeneralRequestInfo(
              requestInfo.getTaskType(),
              TaskStatus.SENT,
              requestInfo.getRequestId(),
              requestInfo.getFuture());
      session.updateRequestInfo(requestId, sentRequestInfo);
      tryToSendAwaitTaskIfAny(session, outgoingPipeline, scheduler);
    }
  }
}
