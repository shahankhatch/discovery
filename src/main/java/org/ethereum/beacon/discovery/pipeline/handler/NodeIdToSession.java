/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ethereum.beacon.discovery.pipeline.handler;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.ethereum.beacon.discovery.network.NetworkParcelV5;
import org.ethereum.beacon.discovery.pipeline.Envelope;
import org.ethereum.beacon.discovery.pipeline.EnvelopeHandler;
import org.ethereum.beacon.discovery.pipeline.Field;
import org.ethereum.beacon.discovery.pipeline.HandlerUtil;
import org.ethereum.beacon.discovery.pipeline.Pipeline;
import org.ethereum.beacon.discovery.scheduler.ExpirationScheduler;
import org.ethereum.beacon.discovery.schema.EnrFieldV4;
import org.ethereum.beacon.discovery.schema.FixedNodeIdSchemaInterpreter;
import org.ethereum.beacon.discovery.schema.IdentitySchemaV4Interpreter;
import org.ethereum.beacon.discovery.schema.NodeRecord;
import org.ethereum.beacon.discovery.schema.NodeRecordFactory;
import org.ethereum.beacon.discovery.schema.NodeRecordInfo;
import org.ethereum.beacon.discovery.schema.NodeSession;
import org.ethereum.beacon.discovery.storage.AuthTagRepository;
import org.ethereum.beacon.discovery.storage.NodeBucketStorage;
import org.ethereum.beacon.discovery.storage.NodeTable;
import org.javatuples.Pair;

/**
 * Performs {@link Field#SESSION_LOOKUP} request. Looks up for Node session based on NodeId, which
 * should be in request field and stores it in {@link Field#SESSION} field.
 */
public class NodeIdToSession implements EnvelopeHandler {

  private static final int CLEANUP_DELAY_SECONDS = 180;
  private static final Logger logger = LogManager.getLogger(NodeIdToSession.class);
  private final NodeRecord homeNodeRecord;
  private final Bytes staticNodeKey;
  private final NodeBucketStorage nodeBucketStorage;
  private final AuthTagRepository authTagRepo;
  private final Map<Bytes, NodeSession> recentSessions =
      new ConcurrentHashMap<>(); // nodeId -> session
  private final NodeTable nodeTable;
  private final Pipeline outgoingPipeline;
  private ExpirationScheduler<Bytes> sessionExpirationScheduler =
      new ExpirationScheduler<>(CLEANUP_DELAY_SECONDS, TimeUnit.SECONDS);

  public NodeIdToSession(
      NodeRecord homeNodeRecord,
      Bytes staticNodeKey,
      NodeBucketStorage nodeBucketStorage,
      AuthTagRepository authTagRepo,
      NodeTable nodeTable,
      Pipeline outgoingPipeline) {
    this.homeNodeRecord = homeNodeRecord;
    this.staticNodeKey = staticNodeKey;
    this.nodeBucketStorage = nodeBucketStorage;
    this.authTagRepo = authTagRepo;
    this.nodeTable = nodeTable;
    this.outgoingPipeline = outgoingPipeline;
  }

  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void handle(Envelope envelope) {
    logger.trace(
        () ->
            String.format(
                "Envelope %s in NodeIdToSession, checking requirements satisfaction",
                envelope.getId()));
    if (!HandlerUtil.requireField(Field.SESSION_LOOKUP, envelope)) {
      return;
    }
    logger.trace(
        () ->
            String.format(
                "Envelope %s in NodeIdToSession, requirements are satisfied!", envelope.getId()));

    Pair<Bytes, Runnable> sessionRequest =
        (Pair<Bytes, Runnable>) envelope.get(Field.SESSION_LOOKUP);
    envelope.remove(Field.SESSION_LOOKUP);
    logger.trace(
        () ->
            String.format(
                "Envelope %s: Session lookup requested for nodeId %s",
                envelope.getId(), sessionRequest.getValue0()));
    Optional<NodeSession> nodeSessionOptional = getSession(envelope, sessionRequest.getValue0());
    if (nodeSessionOptional.isPresent()) {
      envelope.put(Field.SESSION, nodeSessionOptional.get());
      logger.trace(
          () ->
              String.format(
                  "On %s, Session resolved: %s in envelope #%s", this.homeNodeRecord,
                  nodeSessionOptional.get(), envelope.getId()));
    } else {
      logger.debug(
          () ->
              String.format(
                  "On %s, Envelope %s: Session not resolved for nodeId %s", this.homeNodeRecord,
                  envelope.getId(), sessionRequest.getValue0()));
      sessionRequest.getValue1().run();
    }
  }

  private Optional<NodeSession> getSession(Envelope envelope, Bytes nodeId) {
    NodeSession context = recentSessions.get(nodeId);
    if (context == null) {
      Optional<NodeRecordInfo> nodeOptional = nodeTable.getNode(nodeId);
      if (nodeOptional.isEmpty()) {
        logger.trace(
            () -> String.format("Couldn't find node record for nodeId %s, ignoring", nodeId));
//        return Optional.empty();
        NodeRecord tempRecord = NodeRecord
            .fromValues(new FixedNodeIdSchemaInterpreter(new IdentitySchemaV4Interpreter(), nodeId),
                UInt64.ZERO,
                List.of(new Pair<>(EnrFieldV4.IP_V4, envelope.get(Field.IP)),
                    new Pair<>(EnrFieldV4.UDP_V4, ((Bytes) envelope.get(Field.PORT)).toInt())));
        nodeOptional = Optional.of(NodeRecordInfo.createDefault(tempRecord));

      }
      NodeRecord nodeRecord = nodeOptional.get().getNode();

      SecureRandom random = new SecureRandom();

      context =
          new NodeSession(
              nodeRecord,
              homeNodeRecord,
              staticNodeKey,
              nodeTable,
              nodeBucketStorage,
              authTagRepo,
              packet -> outgoingPipeline.push(new NetworkParcelV5(packet, nodeRecord)),
              random);
      recentSessions.put(nodeId, context);
    }

    final NodeSession contextBackup = context;
    sessionExpirationScheduler.put(
        context.getNodeRecord().getNodeId(),
        () -> {
          recentSessions.remove(contextBackup.getNodeRecord().getNodeId());
          contextBackup.cleanup();
        });
    return Optional.of(context);
  }
}
