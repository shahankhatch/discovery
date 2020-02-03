/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ethereum.beacon.discovery.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.tuweni.bytes.Bytes;
import org.ethereum.beacon.discovery.schema.NodeRecordFactory;
import org.ethereum.beacon.discovery.schema.NodeRecordInfo;
import org.ethereum.beacon.discovery.schema.NodeStatus;
import org.web3j.rlp.RlpDecoder;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;

/**
 * Storage for nodes, K-Bucket. Holds only {@link #K} nodes, replacing nodes with the same nodeId
 * and nodes with old lastRetry. Also throws out DEAD nodes without taking any notice on other
 * fields.
 */
public class NodeBucket {
  /** Bucket size, number of nodes */
  public static final int K = 16;

  private final ArrayList<NodeRecordInfo> bucket = new ArrayList<>();

  public static NodeBucket fromRlpBytes(Bytes bytes, NodeRecordFactory nodeRecordFactory) {
    NodeBucket nodeBucket = new NodeBucket();
    ((RlpList) RlpDecoder.decode(bytes.toArray()).getValues().get(0))
        .getValues().stream()
            .map(rt -> (RlpString) rt)
            .map(RlpString::getBytes)
            .map(Bytes::wrap)
            .map((Bytes bytes1) -> NodeRecordInfo.fromRlpBytes(bytes1, nodeRecordFactory))
            .forEach(nodeBucket::put);
    return nodeBucket;
  }

  public synchronized boolean put(NodeRecordInfo nodeRecord) {
    return bucket.add(nodeRecord);
  }

  public boolean contains(NodeRecordInfo nodeRecordInfo) {
    return bucket.contains(nodeRecordInfo);
  }

  public void putAll(Collection<NodeRecordInfo> nodeRecords) {
    nodeRecords.forEach(this::put);
  }

  public synchronized Bytes toRlpBytes() {
    byte[] res =
        RlpEncoder.encode(
            new RlpList(
                bucket.stream()
                    .map(NodeRecordInfo::toRlpBytes)
                    .map(Bytes::toArray)
                    .map(RlpString::create)
                    .collect(Collectors.toList())));
    return Bytes.wrap(res);
  }

  public int size() {
    return bucket.size();
  }

  public List<NodeRecordInfo> getNodeRecords() {
    return bucket;
  }
}
