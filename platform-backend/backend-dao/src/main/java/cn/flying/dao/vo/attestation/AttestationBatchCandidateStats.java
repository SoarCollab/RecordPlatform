package cn.flying.dao.vo.attestation;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 租户内生产存证候选的聚合状态。
 */
@Getter
@Setter
public class AttestationBatchCandidateStats {

    private long readyCount;

    private long claimedCount;

    private long batchedCount;

    private long deadLetterCount;

    private Date oldestReadyAt;
}
