/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ethereum.beacon.discovery.schema;

import static org.ethereum.beacon.discovery.schema.EnrField.IP_V4;
import static org.ethereum.beacon.discovery.schema.EnrField.UDP_V4;

import com.google.common.base.Objects;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.ethereum.beacon.discovery.util.Functions;
import org.javatuples.Pair;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;

/**
 * Ethereum Node Record V4
 *
 * <p>Node record as described in <a href="https://eips.ethereum.org/EIPS/eip-778">EIP-778</a>
 */
public class NodeRecord {
  /**
   * The canonical encoding of a node record is an RLP list of [signature, seq, k, v, ...]. The
   * maximum encoded size of a node record is 300 bytes. Implementations should reject records
   * larger than this size.
   */
  public static final int MAX_ENCODED_SIZE = 300;

  private static final EnrFieldInterpreter enrFieldInterpreter = EnrFieldInterpreterV4.DEFAULT;
  private final UInt64 seq;
  // Signature
  private Bytes signature;
  // optional fields
  private Map<String, Object> fields = new HashMap<>();
  private IdentitySchemaInterpreter identitySchemaInterpreter;

  public void setIdentitySchemaInterpreter(IdentitySchemaInterpreter schemaInterpreter) {
    this.identitySchemaInterpreter = schemaInterpreter;
  }

  private NodeRecord(
      IdentitySchemaInterpreter identitySchemaInterpreter, UInt64 seq, Bytes signature) {
    this.seq = seq;
    this.signature = signature;
    this.identitySchemaInterpreter = identitySchemaInterpreter;
  }

  private NodeRecord(IdentitySchemaInterpreter identitySchemaInterpreter, UInt64 seq) {
    this.seq = seq;
    this.signature = MutableBytes.create(96);
    this.identitySchemaInterpreter = identitySchemaInterpreter;
  }

  public static NodeRecord fromValues(
      IdentitySchemaInterpreter identitySchemaInterpreter,
      UInt64 seq,
      List<Pair<String, Object>> fieldKeyPairs) {
    NodeRecord nodeRecord = new NodeRecord(identitySchemaInterpreter, seq);
    fieldKeyPairs.forEach(objects -> nodeRecord.set(objects.getValue0(), objects.getValue1()));
    return nodeRecord;
  }

  @SuppressWarnings({"unchecked", "DefaultCharset"})
  public static NodeRecord fromRawFields(
      IdentitySchemaInterpreter identitySchemaInterpreter,
      UInt64 seq,
      Bytes signature,
      List<RlpType> rawFields) {
    NodeRecord nodeRecord = new NodeRecord(identitySchemaInterpreter, seq, signature);
    for (int i = 0; i < rawFields.size(); i += 2) {
      String key = new String(((RlpString) rawFields.get(i)).getBytes());
      nodeRecord.set(key, enrFieldInterpreter.decode(key, (RlpString) rawFields.get(i + 1)));
    }
    return nodeRecord;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static NodeRecord createNodeRecord(byte[] privateKey, String networkInterface, int port)
      throws UnknownHostException {
    Bytes localAddressBytes =
        Bytes.wrap(InetAddress.getByName(networkInterface).getAddress()); // 172.18.0.2 // 127.0.0.1
    Bytes localIp =
        Bytes.concatenate(Bytes.wrap(new byte[4 - localAddressBytes.size()]), localAddressBytes);
    NodeRecord nodeRecord =
        NodeRecordFactory.DEFAULT.createFromValues(
            UInt64.ZERO,
            Pair.with(EnrField.ID, IdentitySchema.V4),
            Pair.with(IP_V4, localIp),
            Pair.with(
                EnrFieldV4.PKEY_SECP256K1,
                Functions.derivePublicKeyFromPrivate(Bytes.wrap(privateKey))),
            Pair.with(UDP_V4, port));
    nodeRecord.sign(Bytes.wrap(privateKey));
    nodeRecord.verify();
    return nodeRecord;
  }

  @SuppressWarnings({"unchecked", "DefaultCharset"})
  public String asBase64() {
    return new String(Base64.getUrlEncoder().encode(serialize().toArray()));
  }

  public IdentitySchema getIdentityScheme() {
    return identitySchemaInterpreter.getScheme();
  }

  public void set(String key, Object value) {
    fields.put(key, value);
  }

  public Object get(String key) {
    return fields.get(key);
  }

  public UInt64 getSeq() {
    return seq;
  }

  public Bytes getSignature() {
    return signature;
  }

  public void setSignature(Bytes signature) {
    this.signature = signature;
  }

  public Set<String> getKeys() {
    return new HashSet<>(fields.keySet());
  }

  public Object getKey(String key) {
    return fields.get(key);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    NodeRecord that = (NodeRecord) o;
    return Objects.equal(seq, that.seq)
        && Objects.equal(signature, that.signature)
        && Objects.equal(fields, that.fields);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(seq, signature, fields);
  }

  public void verify() {
    identitySchemaInterpreter.verify(this);
  }

  public void sign(Object signOptions) {
    identitySchemaInterpreter.sign(this, signOptions);
  }

  public RlpList asRlp() {
    return asRlpImpl(true);
  }

  public RlpList asRlpNoSignature() {
    return asRlpImpl(false);
  }

  private RlpList asRlpImpl(boolean withSignature) {
    assert getSeq() != null;
    // content   = [seq, k, v, ...]
    // signature = sign(content)
    // record    = [signature, seq, k, v, ...]
    List<RlpType> values = new ArrayList<>();
    if (withSignature) {
      values.add(RlpString.create(getSignature().toArray()));
    }
    values.add(RlpString.create(getSeq().toBigInteger()));
    List<String> keySortedList = fields.keySet().stream().sorted().collect(Collectors.toList());
    for (String key : keySortedList) {
      if (fields.get(key) == null) {
        continue;
      }
      values.add(RlpString.create(key));
      values.add(enrFieldInterpreter.encode(key, fields.get(key)));
    }

    return new RlpList(values);
  }

  public Bytes serialize() {
    return serializeImpl(true);
  }

  public Bytes serializeNoSignature() {
    return serializeImpl(false);
  }

  private Bytes serializeImpl(boolean withSignature) {
    RlpType rlpRecord = withSignature ? asRlp() : asRlpNoSignature();
    byte[] bytes = RlpEncoder.encode(rlpRecord);
    assert bytes.length <= MAX_ENCODED_SIZE;
    return Bytes.wrap(bytes);
  }

  public Bytes getNodeId() {
    return identitySchemaInterpreter.getNodeId(this);
  }

  public Object getPort() {
    return fields.get(EnrFieldV4.UDP_V4);
  }

  public Object getPubKey() {
    return fields.get(EnrFieldV4.PKEY_SECP256K1);
  }

  public Object getIPAddress() {
    return fields.get(EnrFieldV4.IP_V4);
  }
  @Override
  public String toString() {
    return "NodeRecordV4,"+this.getPort()+"{"
        + "publicKey="
        + fields.get(EnrFieldV4.PKEY_SECP256K1)
        + ", ipV4address="
        + fields.get(EnrFieldV4.IP_V4)
        + ", udpPort="
        + fields.get(EnrFieldV4.UDP_V4)
        + ", asBase64="
        + this.asBase64()
        + ", nodeId="
        + this.getNodeId()
        + '}';
  }
}
