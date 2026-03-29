package cn.flying.health;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.BlockChainMessage;
import cn.flying.service.remote.FileRemoteClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.math.BigInteger;

@Slf4j
@Component("fisco")
public class FiscoHealthIndicator implements HealthIndicator {

    @Resource
    private FileRemoteClient fileRemoteClient;

    @Override
    public Health health() {
        try {
            Result<BlockChainMessage> result = fileRemoteClient.getCurrentBlockChainMessage();
            if (result == null || result.getData() == null) {
                return Health.down()
                        .withDetail("reason", "Unable to retrieve blockchain status")
                        .build();
            }

            BlockChainMessage message = result.getData();
            if (message.blockNumber() == null || message.transactionCount() == null || message.failedTransactionCount() == null) {
                return Health.down()
                        .withDetail("reason", "Blockchain status incomplete")
                        .build();
            }

            BigInteger blockNumber = BigInteger.valueOf(message.blockNumber());

            Health.Builder builder = Health.up()
                    .withDetail("blockNumber", message.blockNumber())
                    .withDetail("transactionCount", message.transactionCount())
                    .withDetail("failedTransactionCount", message.failedTransactionCount());

            if (blockNumber.compareTo(BigInteger.ZERO) <= 0) {
                return builder.status("DEGRADED")
                        .withDetail("warning", "Block number is zero, chain may be stalled")
                        .build();
            }

            return builder.build();
        } catch (Exception e) {
            log.error("FISCO health check error", e);
            return Health.down()
                    .withDetail("reason", "Failed to connect to FISCO node")
                    .build();
        }
    }
}
