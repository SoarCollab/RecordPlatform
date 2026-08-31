package cn.flying.config;

import cn.flying.service.monitor.SagaMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bind monitoring consumers to production Saga registration and the actual exporter. */
class SagaPrometheusContractTest {

    /** Export eagerly registered states before traffic and real counter increments afterward. */
    @Test
    void productionCountersExportOneTotalSuffixAndAllFourStates() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            // Registration/counter methods do not access persistence; no DAO substitute is used.
            var metrics = new SagaMetrics(registry, null, null);
            metrics.init();
            assertEquals(Map.of("started", 0.0, "completed", 0.0, "failed", 0.0, "compensated", 0.0),
                    counters(registry.scrape()));
            metrics.recordSagaStarted();
            metrics.recordSagaStarted();
            metrics.recordSagaCompleted();
            metrics.recordSagaFailed();
            metrics.recordSagaCompensated();
            String actual = registry.scrape();
            assertTrue(actual.contains("# TYPE saga_total counter"));
            assertFalse(actual.contains("saga_total_total"));
            assertEquals(Map.of("started", 2.0, "completed", 1.0, "failed", 1.0, "compensated", 1.0),
                    counters(actual));
        } finally {
            registry.close();
        }
    }

    /** Fail when fixtures and rules agree with each other but disagree with real scrape output. */
    @Test
    void checkedInMonitoringConsumersUseTheActuallyExportedFamily() throws IOException {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            new SagaMetrics(registry, null, null).init();
            String actual = registry.scrape();
            Path root = repositoryRoot();
            for (String relative : new String[]{
                    "config/prometheus/recording-rules.yml",
                    "config/prometheus/alerting-rules.yml",
                    "config/prometheus/tests/slo-rules.test.yml"}) {
                String consumer = Files.readString(root.resolve(relative));
                var selectors = Pattern.compile("\\b(saga_[a-z_]+)\\{").matcher(consumer);
                int checked = 0;
                while (selectors.find()) {
                    assertTrue(actual.contains("# TYPE " + selectors.group(1) + " counter"),
                            relative + " references a Saga counter family absent from production scrape");
                    checked++;
                }
                assertTrue(checked > 0, relative + " must retain the Saga monitoring contract");
            }
        } finally {
            registry.close();
        }
    }

    /** Parse real exporter samples without relying on decimal formatting or iteration order. */
    private static Map<String, Double> counters(String scrape) {
        var matcher = Pattern.compile("^saga_total\\{status=\"([^\"]+)\"} ([0-9.Ee+-]+)$",
                Pattern.MULTILINE).matcher(scrape);
        Map<String, Double> result = new HashMap<>();
        while (matcher.find()) {
            result.put(matcher.group(1), Double.parseDouble(matcher.group(2)));
        }
        return result;
    }

    /** Find only this checkout's rule assets whether Maven starts at root or module level. */
    private static Path repositoryRoot() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("config/prometheus/recording-rules.yml"))) {
                return path;
            }
        }
        throw new IllegalStateException("Monitoring rule assets are required by this contract test");
    }
}
