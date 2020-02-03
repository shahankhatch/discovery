package org.ethereum.beacon.discovery.schema;

import org.apache.tuweni.bytes.Bytes;

public class FixedNodeIdSchemaInterpreter implements IdentitySchemaInterpreter {

  private IdentitySchemaInterpreter schemaInterpreter;
  private Bytes nodeId;

  public FixedNodeIdSchemaInterpreter(IdentitySchemaInterpreter schemaInterpreter, Bytes nodeId) {
    this.schemaInterpreter = schemaInterpreter;
    this.nodeId = nodeId;
  }

  @Override
  public Bytes getNodeId(NodeRecord nodeRecord) {
    return nodeId;
  }

  @Override
  public IdentitySchema getScheme() {
    return schemaInterpreter.getScheme();
  }

  @Override
  public void sign(NodeRecord nodeRecord, Object signOptions) {
    schemaInterpreter.sign(nodeRecord, signOptions);
  }

  @Override
  public void verify(NodeRecord nodeRecord) {
    schemaInterpreter.verify(nodeRecord);
  }
}
