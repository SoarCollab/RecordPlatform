package cn.flying.verifier.cli;

import cn.flying.verifier.VerifierTestFixture;
import cn.flying.verifier.crypto.CanonicalJson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI contract tests for stable output formats, exit codes, and argument rejection.
 */
class VerifierCliTest {

    @TempDir
    Path directory;

    private VerifierTestFixture.Fixture fixture;
    private Path trustedKey;

    /** Creates a signed archive and an explicit local trust anchor for each command test. */
    @BeforeEach
    void setUp() throws Exception {
        fixture = new VerifierTestFixture().create(directory);
        trustedKey = directory.resolve("trusted-key.json");
        Files.write(trustedKey, new CanonicalJson().canonicalBytes(fixture.key()));
    }

    /** Emits JSON and exit 3 when local validation succeeds but current status and chain are offline. */
    @Test
    void shouldReturnIndeterminateExitCodeForOfflineTrustDependencies() {
        Invocation invocation = invoke(
                "verify",
                "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(),
                "--trusted-key", trustedKey.toString());

        assertThat(invocation.exitCode()).isEqualTo(VerifierCli.EXIT_INDETERMINATE);
        assertThat(invocation.stdout())
                .contains("\"outcome\":\"INDETERMINATE\"")
                .contains("\"schemaVersion\":\"record-platform-verification-report.v1\"");
        assertThat(invocation.stderr()).isEmpty();
    }

    /** Emits human-readable checks and exit 2 for a deterministic original-file mismatch. */
    @Test
    void shouldReturnInvalidExitCodeForTamperedOriginalInTextMode() throws Exception {
        byte[] tampered = fixture.originalBytes();
        tampered[0] ^= 1;
        Path file = directory.resolve("tampered.txt");
        Files.write(file, tampered);

        Invocation invocation = invoke(
                "verify",
                "--file", file.toString(),
                "--proof", fixture.proof().toString(),
                "--trusted-key", trustedKey.toString(),
                "--format", "text");

        assertThat(invocation.exitCode()).isEqualTo(VerifierCli.EXIT_INVALID);
        assertThat(invocation.stdout())
                .contains("Outcome: INVALID")
                .contains("FILE_HASH_MISMATCH");
    }

    /** Returns exit 0 and the stable JSON contract when explicitly configured online trust passes. */
    @Test
    void shouldReturnValidExitCodeWithExplicitOnlineResolution() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respondWithTrustEvidence);
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            Invocation invocation = invoke(
                    "verify",
                    "--file", fixture.original().toString(),
                    "--proof", fixture.proof().toString(),
                    "--trusted-key", trustedKey.toString(),
                    "--online",
                    "--issuer-base-url", origin + "/issuer",
                    "--chain-url-template",
                    origin + "/chain/{chainType}/{chainId}/{groupId}/{contractAddress}/{batchNo}",
                    "--allow-host", "127.0.0.1",
                    "--allow-http",
                    "--allow-private-addresses");

            assertThat(invocation.exitCode()).isEqualTo(VerifierCli.EXIT_VALID);
            assertThat(invocation.stdout())
                    .contains("\"outcome\":\"VALID\"")
                    .contains("\"schemaVersion\":\"record-platform-verification-report.v1\"")
                    .contains("\"verifierVersion\":\"0.0.2\"");
            assertThat(invocation.stderr()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    /** Maps a safely processed missing original file to the documented error exit code. */
    @Test
    void shouldReturnErrorExitCodeForMissingOriginal() {
        Invocation invocation = invoke(
                "verify",
                "--file", directory.resolve("missing.bin").toString(),
                "--proof", fixture.proof().toString(),
                "--trusted-key", trustedKey.toString());

        assertThat(invocation.exitCode()).isEqualTo(VerifierCli.EXIT_ERROR);
        assertThat(invocation.stdout())
                .contains("\"outcome\":\"ERROR\"")
                .contains("\"code\":\"VERIFICATION_IO_ERROR\"");
    }

    /** Returns help successfully without requiring file paths. */
    @Test
    void shouldRenderHelp() {
        Invocation invocation = invoke("--help");
        Invocation shortHelp = invoke("-h");

        assertThat(invocation.exitCode()).isEqualTo(VerifierCli.EXIT_VALID);
        assertThat(invocation.stdout()).contains("Usage:", "Offline or unavailable");
        assertThat(shortHelp.exitCode()).isEqualTo(VerifierCli.EXIT_VALID);
    }

    /** Returns usage exit 64 for unknown flags and online settings without explicit opt-in. */
    @Test
    void shouldRejectInvalidArguments() {
        Invocation unknown = invoke("verify", "--unknown", "value");
        Invocation implicitNetwork = invoke(
                "verify",
                "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(),
                "--issuer-base-url", "https://issuer.example");
        Invocation incompleteOnline = invoke(
                "verify",
                "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(),
                "--online",
                "--allow-host", "issuer.example");

        assertThat(unknown.exitCode()).isEqualTo(VerifierCli.EXIT_USAGE);
        assertThat(unknown.stderr())
                .contains("Usage error")
                .doesNotContain("--unknown", "value");
        assertThat(implicitNetwork.exitCode()).isEqualTo(VerifierCli.EXIT_USAGE);
        assertThat(implicitNetwork.stderr()).contains("require --online");
        assertThat(incompleteOnline.exitCode()).isEqualTo(VerifierCli.EXIT_USAGE);
        assertThat(incompleteOnline.stderr())
                .contains("--issuer-base-url", "--chain-url-template", "--allow-host");

        Invocation controlCharacters = invoke(
                "verify",
                "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(),
                "--evil\u001b[31m", "value");
        assertThat(controlCharacters.exitCode()).isEqualTo(VerifierCli.EXIT_USAGE);
        assertThat(controlCharacters.stderr()).doesNotContain("\u001b");

        Invocation invalidUri = invoke(
                "verify",
                "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(),
                "--online",
                "--issuer-base-url", "https://issuer.example/[private-value]",
                "--chain-url-template", "https://chain.example/{batchNo}",
                "--allow-host", "issuer.example");
        assertThat(invalidUri.exitCode()).isEqualTo(VerifierCli.EXIT_USAGE);
        assertThat(invalidUri.stderr())
                .contains("--issuer-base-url contains an invalid URI")
                .doesNotContain("private-value");
    }

    /** Rejects missing, duplicate, malformed, and non-positive option values without invoking verification. */
    @Test
    void shouldRejectMalformedOptionValues() {
        Invocation nullArguments = invoke((String[]) null);
        Invocation wrongCommand = invoke("inspect");
        Invocation missingValue = invoke("verify", "--file");
        Invocation optionAsValue = invoke("verify", "--file", "--proof", fixture.proof().toString());
        Invocation missingProof = invoke("verify", "--file", fixture.original().toString());
        Invocation blankFile = invoke(
                "verify", "--file", " ", "--proof", fixture.proof().toString());
        Invocation invalidFormat = invoke(
                "verify", "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(), "--format", "yaml");
        Invocation duplicateFile = invoke(
                "verify", "--file", fixture.original().toString(),
                "--file", fixture.original().toString(), "--proof", fixture.proof().toString());
        Invocation duplicateFlag = invoke(
                "verify", "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(), "--online", "--online");
        Invocation zeroLimit = invoke(
                "verify", "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(), "--max-file-bytes", "0");
        Invocation malformedLimit = invoke(
                "verify", "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(), "--max-file-bytes", "not-a-number");
        Invocation invalidPath = invoke(
                "verify", "--file", "invalid\0path", "--proof", fixture.proof().toString());

        assertThat(nullArguments.exitCode()).isEqualTo(VerifierCli.EXIT_USAGE);
        assertThat(wrongCommand.exitCode()).isEqualTo(VerifierCli.EXIT_USAGE);
        assertThat(missingValue.stderr()).contains("--file requires a value");
        assertThat(optionAsValue.stderr()).contains("--file requires a value");
        assertThat(missingProof.stderr()).contains("--proof is required");
        assertThat(blankFile.stderr()).contains("--file is required");
        assertThat(invalidFormat.stderr()).contains("--format must be json or text");
        assertThat(duplicateFile.stderr()).contains("--file may be supplied only once");
        assertThat(duplicateFlag.stderr()).contains("--online may be supplied only once");
        assertThat(zeroLimit.stderr()).contains("--max-file-bytes must be a positive decimal integer");
        assertThat(malformedLimit.stderr()).contains("--max-file-bytes must be a positive decimal integer");
        assertThat(invalidPath.stderr())
                .contains("--file contains an invalid path")
                .doesNotContain("invalid\0path");
    }

    /** Requires every online prerequisite across issuer, template, host, and opt-in flag combinations. */
    @Test
    void shouldRejectIncompleteOnlineConfigurations() {
        Invocation templateWithoutOnline = invoke(
                "verify", "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(),
                "--chain-url-template", "https://chain.example/{batchNo}");
        Invocation hostWithoutOnline = invoke(
                "verify", "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(), "--allow-host", "issuer.example");
        Invocation httpWithoutOnline = invoke(
                "verify", "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(), "--allow-http");
        Invocation privateWithoutOnline = invoke(
                "verify", "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(), "--allow-private-addresses");
        Invocation missingTemplate = invoke(
                "verify", "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(), "--online",
                "--issuer-base-url", "https://issuer.example", "--allow-host", "issuer.example");
        Invocation blankTemplate = invoke(
                "verify", "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(), "--online",
                "--issuer-base-url", "https://issuer.example",
                "--chain-url-template", " ", "--allow-host", "issuer.example");
        Invocation missingHost = invoke(
                "verify", "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString(), "--online",
                "--issuer-base-url", "https://issuer.example",
                "--chain-url-template", "https://chain.example/{batchNo}");

        assertThat(templateWithoutOnline.stderr()).contains("require --online");
        assertThat(hostWithoutOnline.stderr()).contains("require --online");
        assertThat(httpWithoutOnline.stderr()).contains("require --online");
        assertThat(privateWithoutOnline.stderr()).contains("require --online");
        assertThat(missingTemplate.stderr()).contains("--online requires");
        assertThat(blankTemplate.stderr()).contains("--online requires");
        assertThat(missingHost.stderr()).contains("--online requires");
    }

    /** Exercises disabled and enabled online key resolver selection without a local trust anchor. */
    @Test
    void shouldSelectKeyResolverFromExplicitTrustMode() throws Exception {
        Invocation offline = invoke(
                "verify", "--file", fixture.original().toString(),
                "--proof", fixture.proof().toString());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respondWithTrustEvidence);
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            Invocation online = invoke(
                    "verify", "--file", fixture.original().toString(),
                    "--proof", fixture.proof().toString(), "--online",
                    "--issuer-base-url", origin,
                    "--chain-url-template", origin + "/chain/{batchNo}",
                    "--allow-host", "127.0.0.1", "--allow-http", "--allow-private-addresses");

            assertThat(offline.exitCode()).isEqualTo(VerifierCli.EXIT_INDETERMINATE);
            assertThat(offline.stdout()).contains("SIGNING_KEY_UNAVAILABLE");
            assertThat(online.exitCode()).isEqualTo(VerifierCli.EXIT_INDETERMINATE);
            assertThat(online.stdout()).contains("SIGNING_KEY_UNKNOWN");
        } finally {
            server.stop(0);
        }
    }

    /** Converts unexpected output initialization failures into the stable generic verifier error. */
    @Test
    void shouldHandleUnexpectedRuntimeInitializationFailure() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = VerifierCli.run(
                new String[]{"--help"},
                null,
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertThat(exit).isEqualTo(VerifierCli.EXIT_ERROR);
        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .contains("unable to initialize the requested verification context");
    }

    /** Serves issuer status and direct chain evidence for the valid online CLI vector. */
    private void respondWithTrustEvidence(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] body;
        if (path.contains("/api/v1/public/proofs/")) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("issuedAt", fixture.status().issuedAt());
            status.put("issuedStatus", fixture.status().issuedStatus());
            status.put("keyId", fixture.status().keyId());
            status.put("keyVersion", fixture.status().keyVersion());
            status.put("proofId", fixture.status().proofId());
            status.put("reason", null);
            status.put("status", fixture.status().status());
            status.put("statusVersion", 1);
            status.put("updatedAt", fixture.status().updatedAt());
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("code", 200);
            envelope.put("data", status);
            envelope.put("message", "success");
            body = new CanonicalJson().canonicalBytes(envelope);
        } else if (path.startsWith("/chain/")) {
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("batchNo", fixture.chain().batchNo());
            chain.put("blockNumber", fixture.chain().blockNumber());
            chain.put("chainId", fixture.chain().chainId());
            chain.put("chainType", fixture.chain().chainType());
            chain.put("contractAddress", fixture.chain().contractAddress());
            chain.put("groupId", fixture.chain().groupId());
            chain.put("merkleRoot", fixture.chain().merkleRoot());
            chain.put("schemaVersion", fixture.chain().schemaVersion());
            chain.put("transactionHash", fixture.chain().transactionHash());
            body = new CanonicalJson().canonicalBytes(chain);
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /** Captures one in-process CLI invocation without terminating the test JVM. */
    private Invocation invoke(String... arguments) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = VerifierCli.run(
                arguments,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Invocation(
                exit,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    /** Captured process result for concise command assertions. */
    private record Invocation(int exitCode, String stdout, String stderr) {
    }
}
