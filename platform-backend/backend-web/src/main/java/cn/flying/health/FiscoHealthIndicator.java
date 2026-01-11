package cn.flying.health;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.response.BlockChainMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.concurrent.*;

@Slf4j
@Component("fisco")
public class FiscoHealthIndicator implements HealthIndicator {

    @DubboReference(id = "blockChainServiceFiscoHealth", version = BlockChainService.VERSION, timeout = 3000, retries = 0, providedBy = "RecordPlatform_fisco")
    private BlockChainService blockChainService;

    private static final int TIMEOUT_SECONDS = 3;

    @Resource(name = "healthIndicatorExecutor")
    private ExecutorService executor;

    @Override
    public Health health() {
        Future<Health> future = executor.submit(this::checkFiscoHealth);
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("FISCO health check timed out");
            return Health.down()
                    .withDetail("reason", "Health check timed out after " + TIMEOUT_SECONDS + "s")
                    .build();
        } catch (Exception e) {
            log.error("FISCO health check failed", e);
            return Health.down()
                    .withException(e)
                    .build();
        }
    }

    private Health checkFiscoHealth() {
        try {
            Result<BlockChainMessage> result = blockChainService.getCurrentBlockChainMessage();
            if (result == null || result.getData() == null) {
                return Health.down()
                        .withDetail("reason", "Unable to retrieve blockchain status")
                        .build();
            }

            BlockChainMessage message = result.getData();
            if (message.getBlockNumber() == null || message.getTransactionCount() == null || message.getFailedTransactionCount() == null) {
                return Health.down()
                        .withDetail("reason", "Blockchain status incomplete")
                        .build();
            }

            BigInteger blockNumber = BigInteger.valueOf(message.getBlockNumber());

            Health.Builder builder = Health.up()
                    .withDetail("blockNumber", message.getBlockNumber())
                    .withDetail("transactionCount", message.getTransactionCount())
                    .withDetail("failedTransactionCount", message.getFailedTransactionCount());

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
                    .withException(e)
                    .build();
        }
    }
}
