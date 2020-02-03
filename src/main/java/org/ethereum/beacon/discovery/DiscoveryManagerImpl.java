/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ethereum.beacon.discovery;

import com.google.common.annotations.VisibleForTesting;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.tuweni.bytes.Bytes;
import org.ethereum.beacon.discovery.network.DiscoveryClient;
import org.ethereum.beacon.discovery.network.NettyDiscoveryClientImpl;
import org.ethereum.beacon.discovery.network.NettyDiscoveryServer;
import org.ethereum.beacon.discovery.network.NettyDiscoveryServerImpl;
import org.ethereum.beacon.discovery.network.NetworkParcel;
import org.ethereum.beacon.discovery.pipeline.Envelope;
import org.ethereum.beacon.discovery.pipeline.Field;
import org.ethereum.beacon.discovery.pipeline.Pipeline;
import org.ethereum.beacon.discovery.pipeline.PipelineImpl;
import org.ethereum.beacon.discovery.pipeline.handler.AuthHeaderMessagePacketHandler;
import org.ethereum.beacon.discovery.pipeline.handler.BadPacketHandler;
import org.ethereum.beacon.discovery.pipeline.handler.IncomingDataPacker;
import org.ethereum.beacon.discovery.pipeline.handler.MessageHandler;
import org.ethereum.beacon.discovery.pipeline.handler.MessagePacketHandler;
import org.ethereum.beacon.discovery.pipeline.handler.NewTaskHandler;
import org.ethereum.beacon.discovery.pipeline.handler.NextTaskHandler;
import org.ethereum.beacon.discovery.pipeline.handler.NodeIdToSession;
import org.ethereum.beacon.discovery.pipeline.handler.NodeSessionRequestHandler;
import org.ethereum.beacon.discovery.pipeline.handler.NotExpectedIncomingPacketHandler;
import org.ethereum.beacon.discovery.pipeline.handler.OutgoingParcelHandler;
import org.ethereum.beacon.discovery.pipeline.handler.UnknownPacketTagToSender;
import org.ethereum.beacon.discovery.pipeline.handler.UnknownPacketTypeByStatus;
import org.ethereum.beacon.discovery.pipeline.handler.WhoAreYouAttempt;
import org.ethereum.beacon.discovery.pipeline.handler.WhoAreYouPacketHandler;
import org.ethereum.beacon.discovery.pipeline.handler.WhoAreYouSessionResolver;
import org.ethereum.beacon.discovery.scheduler.Scheduler;
import org.ethereum.beacon.discovery.schema.EnrField;
import org.ethereum.beacon.discovery.schema.NodeRecord;
import org.ethereum.beacon.discovery.schema.NodeRecordFactory;
import org.ethereum.beacon.discovery.storage.AuthTagRepository;
import org.ethereum.beacon.discovery.storage.NodeBucketStorage;
import org.ethereum.beacon.discovery.storage.NodeTable;
import org.ethereum.beacon.discovery.task.TaskOptions;
import org.ethereum.beacon.discovery.task.TaskType;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.ReplayProcessor;

public class DiscoveryManagerImpl implements DiscoveryManager {
  private final ReplayProcessor<NetworkParcel> outgoingMessages = ReplayProcessor.cacheLast();
  private final FluxSink<NetworkParcel> outgoingSink = outgoingMessages.sink();
  private final NettyDiscoveryServer discoveryServer;
  private final Scheduler scheduler;
  private final Pipeline incomingPipeline = new PipelineImpl();
  private final Pipeline outgoingPipeline = new PipelineImpl();
  private DiscoveryClient discoveryClient;
  private CountDownLatch clientStarted = new CountDownLatch(1);

  public DiscoveryManagerImpl(
      NodeTable nodeTable,
      NodeBucketStorage nodeBucketStorage,
      NodeRecord homeNode,
      Bytes homeNodePrivateKey,
      NodeRecordFactory nodeRecordFactory,
      Scheduler serverScheduler,
      Scheduler taskScheduler) {
    AuthTagRepository authTagRepo = new AuthTagRepository();
    this.scheduler = serverScheduler;
    this.discoveryServer =
        new NettyDiscoveryServerImpl(
            ((Bytes) homeNode.get(EnrField.IP_V4)), (int) homeNode.get(EnrField.UDP_V4));
    discoveryServer.useDatagramChannel(
        channel -> {
          discoveryClient = new NettyDiscoveryClientImpl(outgoingMessages, channel);
          clientStarted.countDown();
        });
    NodeIdToSession nodeIdToSession =
        new NodeIdToSession(
            homeNode,
            homeNodePrivateKey,
            nodeBucketStorage,
            authTagRepo,
            nodeTable,
            outgoingPipeline);
    incomingPipeline
        .addHandler(new IncomingDataPacker(homeNode))
        .addHandler(new WhoAreYouAttempt(homeNode.getNodeId()))
        .addHandler(new WhoAreYouSessionResolver(homeNode, authTagRepo))
        .addHandler(new UnknownPacketTagToSender(homeNode))
        .addHandler(nodeIdToSession)
        .addHandler(new UnknownPacketTypeByStatus())
        .addHandler(new NotExpectedIncomingPacketHandler())
        .addHandler(new WhoAreYouPacketHandler(outgoingPipeline, taskScheduler))
        .addHandler(
            new AuthHeaderMessagePacketHandler(outgoingPipeline, taskScheduler, nodeRecordFactory, nodeTable))
        .addHandler(new MessagePacketHandler())
        .addHandler(new MessageHandler(homeNode, nodeRecordFactory))
        .addHandler(new BadPacketHandler());
    outgoingPipeline
        .addHandler(new OutgoingParcelHandler(outgoingSink))
        .addHandler(new NodeSessionRequestHandler())
        .addHandler(nodeIdToSession)
        .addHandler(new NewTaskHandler())
        .addHandler(new NextTaskHandler(homeNode, outgoingPipeline, taskScheduler));
  }

  @Override
  public void start() {
    incomingPipeline.build();
    outgoingPipeline.build();
    Flux.from(discoveryServer.getIncomingPackets()).subscribe(incomingPipeline::push);
    discoveryServer.start(scheduler);
    try {
      clientStarted.await(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new RuntimeException("Failed to start client", e);
    }
  }

  @Override
  public void stop() {
    discoveryServer.stop();
  }

  private CompletableFuture<Void> executeTaskImpl(
      NodeRecord nodeRecord, TaskType taskType, TaskOptions taskOptions) {
    Envelope envelope = new Envelope();
    envelope.put(Field.NODE, nodeRecord);
    CompletableFuture<Void> future = new CompletableFuture<>();
    envelope.put(Field.TASK, taskType);
    envelope.put(Field.FUTURE, future);
    envelope.put(Field.TASK_OPTIONS, taskOptions);
    outgoingPipeline.push(envelope);
    return future;
  }

  @Override
  public CompletableFuture<Void> findNodes(NodeRecord nodeRecord, int distance) {
    return executeTaskImpl(nodeRecord, TaskType.FINDNODE, new TaskOptions(true, distance));
  }

  @Override
  public CompletableFuture<Void> ping(NodeRecord nodeRecord) {
    return executeTaskImpl(nodeRecord, TaskType.PING, new TaskOptions(true));
  }

  @VisibleForTesting
  public Publisher<NetworkParcel> getOutgoingMessages() {
    return outgoingMessages;
  }
}
