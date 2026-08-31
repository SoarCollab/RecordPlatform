package cn.flying.fisco_bcos.monitor;

import cn.flying.fisco_bcos.adapter.BlockChainAdapter;
import cn.flying.fisco_bcos.adapter.model.ChainType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Runs the actual deployed Java agent and inspects its real OTLP JSON exporter, without network I/O. */
class FiscoMetricsOtelExportTest {

    // Pinned to Maven Central's opentelemetry-javaagent/2.26.1/*.jar.sha256 release checksum.
    private static final String AGENT_SHA256 =
            "cc4af5966ab72109cacc962ba3b9f99b3e88caf064c3144a451bcfe0f4950f19";
    private static final List<String> OPERATIONS = List.of("storeFile", "queryFile", "deleteFile", "shareFile");
    private static final List<Double> EXPECTED_BOUNDS =
            List.of(0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 7.5, 10.0, 30.0, 60.0);

    @TempDir
    Path temporaryDirectory;

    /** Finite, second-based buckets must account for samples below, at, and above the five-second SLO. */
    @Test
    void productionTimersShouldExportFiniteBucketsThroughPinnedAgent() throws Exception {
        List<JsonNode> metrics = exportMetrics("record");
        List<JsonNode> histograms = metrics.stream()
                .filter(metric -> metric.path("name").asText().equals("blockchain.operation.duration"))
                .toList();
        assertThat(histograms).hasSize(1);
        JsonNode histogram = histograms.getFirst();
        assertThat(histogram.path("unit").asText()).isEqualTo("s");
        JsonNode points = histogram.path("histogram").path("dataPoints");
        assertThat(points.size()).isEqualTo(4);
        List<String> observedOperations = new ArrayList<>();
        for (JsonNode point : points) {
            List<Double> bounds = new ArrayList<>();
            point.path("explicitBounds").forEach(bound -> bounds.add(bound.asDouble()));
            assertThat(bounds).hasSize(EXPECTED_BOUNDS.size());
            for (int index = 0; index < bounds.size(); index++) {
                // The bridge converts nanoseconds with floating-point multiplication.
                assertThat(bounds.get(index)).isCloseTo(EXPECTED_BOUNDS.get(index), within(1e-12));
            }
            List<Long> counts = new ArrayList<>();
            point.path("bucketCounts").forEach(count -> counts.add(count.asLong()));
            assertThat(counts).containsExactly(0L, 0L, 0L, 0L, 1L, 0L, 1L, 0L, 1L, 0L, 0L, 0L);
            assertThat(point.path("count").asLong()).isEqualTo(3);
            assertThat(point.path("sum").asDouble()).isEqualTo(16.0);
            assertThat(attribute(point, "chain")).isEqualTo("local-fisco");
            observedOperations.add(attribute(point, "operation"));
        }
        assertThat(observedOperations).containsExactlyInAnyOrderElementsOf(OPERATIONS);
    }

    /** Eager gauges prove export works, while unused operation timers must not invent observations. */
    @Test
    void idleTimersShouldNotExportSyntheticLatencyObservations() throws Exception {
        List<JsonNode> metrics = exportMetrics("idle");
        assertThat(metrics).anyMatch(metric -> metric.path("name").asText().equals("blockchain.health"));
        assertThat(metrics).noneMatch(metric -> metric.path("name").asText().equals("blockchain.operation.duration"));
    }

    /** Forks a bounded, credential-free process and collects the agent's shutdown metric export. */
    private List<JsonNode> exportMetrics(String mode) throws Exception {
        Path agent = Path.of(System.getProperty("fisco.test.otel-agent"));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(agent)))).isEqualTo(AGENT_SHA256);
        Path output = temporaryDirectory.resolve(mode + ".log");
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-XX:ActiveProcessorCount=2", "-javaagent:" + agent,
                "-Duser.home=" + temporaryDirectory,
                "-Dotel.instrumentation.common.default-enabled=false",
                "-Dotel.instrumentation.micrometer.enabled=true",
                "-Dotel.service.name=fisco-metrics-contract-test",
                "-Dotel.metrics.exporter=logging-otlp", "-Dotel.traces.exporter=none", "-Dotel.logs.exporter=none",
                "-Dotel.metric.export.interval=600000",
                "-cp", System.getProperty("surefire.test.class.path"), Fixture.class.getName(), mode)
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().clear();
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("agent fixture exits within deadline").isTrue();
            assertThat(Files.size(output)).isLessThan(2 * 1024 * 1024);
            String text = Files.readString(output);
            assertThat(process.exitValue()).as(text).isZero();
            ObjectMapper mapper = new ObjectMapper();
            List<JsonNode> metrics = new ArrayList<>();
            for (String line : text.lines().toList()) {
                int start = line.indexOf("{\"resource\"");
                if (start < 0) {
                    continue;
                }
                JsonNode resource = mapper.readTree(line.substring(start));
                for (JsonNode scope : resource.path("scopeMetrics")) {
                    if (scope.path("scope").path("name").asText().equals("io.opentelemetry.micrometer-1.5")) {
                        scope.path("metrics").forEach(metrics::add);
                    }
                }
            }
            assertThat(metrics).as(text).isNotEmpty();
            return metrics;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /** Reads an exported string attribute without assuming the order of the OTLP attribute list. */
    private String attribute(JsonNode point, String name) {
        for (JsonNode attribute : point.path("attributes")) {
            if (attribute.path("key").asText().equals(name)) {
                return attribute.path("value").path("stringValue").asText();
            }
        }
        throw new AssertionError("Missing metric attribute: " + name);
    }

    /** Standalone numeric fixture: real metric registration, no application bootstrap or chain call. */
    public static final class Fixture {
        private static FiscoMetrics metrics;

        /** Records deterministic durations; JVM shutdown flushes the actual agent exporter once. */
        public static void main(String[] arguments) {
            BlockChainAdapter adapter = (BlockChainAdapter) Proxy.newProxyInstance(
                    Fixture.class.getClassLoader(), new Class<?>[]{BlockChainAdapter.class},
                    (proxy, method, values) -> {
                        if (method.getName().equals("getChainType")) {
                            return ChainType.LOCAL_FISCO;
                        }
                        throw new AssertionError("No chain operation is allowed in the metric fixture");
                    });
            metrics = new FiscoMetrics(Metrics.globalRegistry, adapter);
            metrics.init();
            if (arguments[0].equals("record")) {
                for (String operation : OPERATIONS) {
                    var timer = Metrics.globalRegistry.get("blockchain.operation.duration")
                            .tag("operation", operation).timer();
                    timer.record(Duration.ofSeconds(1));
                    timer.record(Duration.ofSeconds(5));
                    timer.record(Duration.ofSeconds(10));
                }
            }
        }
    }
}
