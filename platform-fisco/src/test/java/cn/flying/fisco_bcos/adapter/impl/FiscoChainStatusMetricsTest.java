package cn.flying.fisco_bcos.adapter.impl;

import cn.flying.fisco_bcos.adapter.model.ChainStatus;
import cn.flying.fisco_bcos.monitor.FiscoMetrics;
import cn.flying.fisco_bcos.service.SharingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.client.protocol.response.TotalTransactionCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Verifies real SDK response DTOs through the production service, adapter, and metric gauges. */
@ExtendWith(MockitoExtension.class)
class FiscoChainStatusMetricsTest {

    @Mock
    private Client client;

    private LocalFiscoAdapter adapter;

    /** Replaces only the external SDK boundary; internal service and adapter behavior remain real. */
    @BeforeEach
    void setUp() {
        SharingService sharingService = new SharingService();
        ReflectionTestUtils.setField(sharingService, "client", client);
        adapter = new LocalFiscoAdapter();
        ReflectionTestUtils.setField(adapter, "sharingService", sharingService);
    }

    /** SDK 3.x decimal quantities must reach gauges unchanged, including the live 54/54/2 case. */
    @Test
    void decimalSdkResponseShouldReachGaugesWithoutHexReinterpretation() throws Exception {
        TotalTransactionCount response = new ObjectMapper().readValue("""
                {"result":{"blockNumber":54,"transactionCount":54,"failedTransactionCount":2}}
                """, TotalTransactionCount.class);
        assertThat(response.getTotalTransactionCount().getBlockNumber()).isEqualTo("54");
        when(client.getTotalTransactionCount()).thenReturn(response);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            FiscoMetrics metrics = new FiscoMetrics(registry, adapter);
            metrics.init();
            metrics.refreshBlockchainStatus();

            assertThat(registry.get("blockchain.block.height").gauge().value()).isEqualTo(54);
            assertThat(registry.get("blockchain.transactions.total").gauge().value()).isEqualTo(54);
            assertThat(registry.get("blockchain.transactions.failed").gauge().value()).isEqualTo(2);
            assertThat(registry.get("blockchain.health").gauge().value()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    /** Only explicit hexadecimal prefixes select radix sixteen; plain strings remain decimal. */
    @ParameterizedTest
    @CsvSource({
            "0, 0", "10, 10", "54, 54", "054, 54", "0x36, 54", "0X36, 54",
            "0x2a, 42", "0X2A, 42", "9223372036854775807, 9223372036854775807",
            "0x7fffffffffffffff, 9223372036854775807"
    })
    void chainQuantitiesShouldRespectExplicitRadix(String quantity, long expected) {
        assertQuantities(quantity, expected);
    }

    /** Malformed, negative, or overflowing values keep the existing zero fallback without wrapping. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0x", "0X", "garbage", "2a", "-1", "0x-1",
            "9223372036854775808", "0x8000000000000000"})
    void invalidChainQuantitiesShouldNotPolluteGauges(String quantity) {
        assertQuantities(quantity, 0);
    }

    /** Supplies a real SDK DTO and checks all three counters share the same numeric contract. */
    private void assertQuantities(String quantity, long expected) {
        TotalTransactionCount.TransactionCountInfo info = new TotalTransactionCount.TransactionCountInfo();
        info.setBlockNumber(quantity);
        info.setTransactionCount(quantity);
        info.setFailedTransactionCount(quantity);
        TotalTransactionCount response = new TotalTransactionCount();
        response.setResult(info);
        when(client.getTotalTransactionCount()).thenReturn(response);

        ChainStatus status = adapter.getChainStatus();

        assertThat(status.isHealthy()).isTrue();
        assertThat(status.getBlockNumber()).isEqualTo(expected);
        assertThat(status.getTransactionCount()).isEqualTo(expected);
        assertThat(status.getFailedTransactionCount()).isEqualTo(expected);
    }
}
