package cn.flying.fisco_bcos.adapter.impl;

import cn.flying.fisco_bcos.adapter.model.ChainAttestationBatch;
import cn.flying.fisco_bcos.adapter.model.ChainType;
import cn.flying.fisco_bcos.service.SharingService;
import org.fisco.bcos.sdk.v3.transaction.model.dto.CallResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbstractFiscoAdapterAttestationBatchTest {

    @Mock
    private SharingService sharingService;

    @Mock
    private CallResponse callResponse;

    private TestFiscoAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new TestFiscoAdapter(sharingService);
    }

    /**
     * 验证 FISCO 只读调用的扁平返回值被完整解析为跨链模型。
     */
    @Test
    void getAttestationBatch_shouldParseExistingContractRecord() throws Exception {
        byte[] root = new byte[32];
        root[31] = 1;
        when(sharingService.getAttestationBatch(any())).thenReturn(callResponse);
        when(callResponse.getReturnObject()).thenReturn(List.of(
                true,
                "MB-900",
                "SHA-256-MERKLE-V1",
                root,
                BigInteger.valueOf(2),
                BigInteger.valueOf(1_700_000_000_000L)));

        ChainAttestationBatch actual = adapter.getAttestationBatch(7L, 900L);

        assertThat(actual.getExists()).isTrue();
        assertThat(actual.getTenantId()).isEqualTo(7L);
        assertThat(actual.getBatchId()).isEqualTo(900L);
        assertThat(actual.getBatchNo()).isEqualTo("MB-900");
        assertThat(actual.getMerkleRoot()).isEqualTo("0".repeat(63) + "1");
        assertThat(actual.getLeafCount()).isEqualTo(2);
        assertThat(actual.getRecordedTime()).isEqualTo(1_700_000_000_000L);
    }

    /**
     * 验证链上不存在时返回显式 exists=false，而不是抛出查询异常。
     */
    @Test
    void getAttestationBatch_shouldReturnNotFoundModel() throws Exception {
        when(sharingService.getAttestationBatch(any())).thenReturn(callResponse);
        when(callResponse.getReturnObject()).thenReturn(List.of(
                false,
                "",
                "",
                new byte[32],
                BigInteger.ZERO,
                BigInteger.ZERO));

        ChainAttestationBatch actual = adapter.getAttestationBatch(7L, 900L);

        assertThat(actual).usingRecursiveComparison()
                .isEqualTo(ChainAttestationBatch.notFound(7L, 900L));
    }

    /**
     * 为抽象 FISCO adapter 提供只包含查询依赖的测试实现。
     */
    private static final class TestFiscoAdapter extends AbstractFiscoAdapter {

        private final SharingService sharingService;

        private TestFiscoAdapter(SharingService sharingService) {
            this.sharingService = sharingService;
        }

        @Override
        protected SharingService getSharingService() {
            return sharingService;
        }

        @Override
        public ChainType getChainType() {
            return ChainType.LOCAL_FISCO;
        }
    }
}
