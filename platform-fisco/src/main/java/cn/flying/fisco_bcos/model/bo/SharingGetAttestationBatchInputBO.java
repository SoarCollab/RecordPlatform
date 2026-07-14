package cn.flying.fisco_bcos.model.bo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Sharing 合约批量存证查询参数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SharingGetAttestationBatchInputBO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String tenantId;

    private BigInteger batchId;

    /**
     * 按合约参数顺序生成调用参数。
     */
    public List<Object> toArgs() {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(batchId);
        return args;
    }
}
