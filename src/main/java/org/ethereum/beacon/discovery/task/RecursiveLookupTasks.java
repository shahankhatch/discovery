/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ethereum.beacon.discovery.task;

import com.google.common.collect.Sets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.ethereum.beacon.discovery.DiscoveryManager;
import org.ethereum.beacon.discovery.scheduler.ExpirationScheduler;
import org.ethereum.beacon.discovery.scheduler.Scheduler;
import org.ethereum.beacon.discovery.schema.NodeRecord;
import org.ethereum.beacon.discovery.schema.NodeRecordInfo;

/**
 * Sends {@link TaskType#FINDNODE} to closest NodeRecords added via {@link #add(NodeRecordInfo,
 * Runnable, Runnable)}. Tasks is called failed if timeout is reached and reply from node is not
 * received.
 */
public class RecursiveLookupTasks {
  private static final Logger logger = LogManager.getLogger();
  private static final int DEFAULT_DISTANCE = 255;
  private final Scheduler scheduler;
  private final NodeRecord nodeRecord;
  private final DiscoveryManager discoveryManager;
  private final Set<Bytes> currentTasks = Sets.newConcurrentHashSet();
  private final ExpirationScheduler<Bytes> taskTimeouts;

  public RecursiveLookupTasks(
      NodeRecord nodeRecord, DiscoveryManager discoveryManager, Scheduler scheduler, Duration timeout) {
    this.nodeRecord = nodeRecord;
    this.discoveryManager = discoveryManager;
    this.scheduler = scheduler;
    this.taskTimeouts =
        new ExpirationScheduler<>(timeout.get(ChronoUnit.NANOS), TimeUnit.NANOSECONDS);
  }

  public void add(NodeRecordInfo nodeRecordInfo, Runnable successCallback, Runnable failCallback) {
    synchronized (this) {
      if (currentTasks.contains(nodeRecordInfo.getNode().getNodeId())) {
        logger.debug(() -> String.format("On %s, currentTasks already contains nodeRecordInfo: %s", this.nodeRecord, nodeRecordInfo));
        return;
      }
      logger.debug(() -> String.format("On %s, currentTasks adding nodeRecordInfo: %s", this.nodeRecord, nodeRecordInfo));
      currentTasks.add(nodeRecordInfo.getNode().getNodeId());
    }

    scheduler.execute(
        () -> {
          CompletableFuture<Void> retry =
              discoveryManager.findNodes(nodeRecordInfo.getNode(), DEFAULT_DISTANCE);
          taskTimeouts.put(
              nodeRecordInfo.getNode().getNodeId(),
              () ->
                  retry.completeExceptionally(
                      new RuntimeException("Timeout for node recursive lookup task")));
          retry.whenComplete(
              (aVoid, throwable) -> {
                if (throwable != null) {
                  failCallback.run();
                  currentTasks.remove(nodeRecordInfo.getNode().getNodeId());
                } else {
                  logger.debug(() -> String.format("On %s, currentTask callback completed successfully: %s", this.nodeRecord, nodeRecordInfo));
                  successCallback.run();
                  currentTasks.remove(nodeRecordInfo.getNode().getNodeId());
                }
              });
        });
  }
}
