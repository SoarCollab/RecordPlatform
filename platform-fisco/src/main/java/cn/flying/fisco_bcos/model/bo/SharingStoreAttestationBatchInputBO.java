package cn.flying.fisco_bcos.model.bo;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SharingStoreAttestationBatchInputBO implements Serializable {
  @Serial
  private static final long serialVersionUID = 1L;

  private String tenantId;

  private BigInteger batchId;

  private String batchNo;

  private String proofAlgorithm;

  private byte[] merkleRoot;

  private BigInteger leafCount;

  /**
   * Converts the batch attestation input into Sharing contract argument order.
   */
  public List<Object> toArgs() {
    List<Object> args = new ArrayList<>();
    args.add(tenantId);
    args.add(batchId);
    args.add(batchNo);
    args.add(proofAlgorithm);
    args.add(merkleRoot);
    args.add(leafCount);
    return args;
  }
}
