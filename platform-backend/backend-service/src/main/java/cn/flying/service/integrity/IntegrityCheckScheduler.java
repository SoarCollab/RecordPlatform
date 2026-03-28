package cn.flying.service.integrity;

import cn.flying.dao.vo.file.IntegrityCheckStatsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled trigger for periodic storage integrity checks.
 * Runs daily at 2am by default; controlled via {@code integrity.check.cron}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "integrity.check.enabled", havingValue = "true", matchIfMissing = true)
public class IntegrityCheckScheduler {

    private final IntegrityCheckService integrityCheckService;

    @Scheduled(cron = "${integrity.check.cron:0 0 2 * * ?}")
    public void runScheduledCheck() {
        log.info("[integrity-check] scheduled check started");
        long start = System.currentTimeMillis();

        IntegrityCheckStatsVO stats = integrityCheckService.checkIntegrity();

        long elapsed = System.currentTimeMillis() - start;
        log.info("[integrity-check] scheduled check completed in {}ms: checked={}, mismatches={}, errors={}",
                elapsed, stats.totalChecked(), stats.mismatchesFound(), stats.errorsEncountered());
    }
}
