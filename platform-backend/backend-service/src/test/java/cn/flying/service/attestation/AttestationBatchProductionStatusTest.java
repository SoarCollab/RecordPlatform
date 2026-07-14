package cn.flying.service.attestation;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class AttestationBatchProductionStatusTest {

    /**
     * 验证构造入参与 accessor 返回值都不能反向修改状态中的日期快照。
     */
    @Test
    void shouldDefensivelyCopyOldestReadyAt() {
        Date source = new Date(1_000L);
        AttestationBatchProductionStatus status = new AttestationBatchProductionStatus(
                true, 1, 10, 60, 20, 2,
                3, 4, 5, 6, source, 7);

        source.setTime(2_000L);
        Date returned = status.oldestReadyAt();
        returned.setTime(3_000L);

        assertThat(status.oldestReadyAt()).isEqualTo(new Date(1_000L));
    }
}
