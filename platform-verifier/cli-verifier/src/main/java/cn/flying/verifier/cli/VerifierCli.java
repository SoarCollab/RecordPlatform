package cn.flying.verifier.cli;

import cn.flying.verifier.DefaultProofVerifier;
import cn.flying.verifier.VerificationContext;
import cn.flying.verifier.VerificationLimits;
import cn.flying.verifier.crypto.CanonicalJson;
import cn.flying.verifier.model.PublicSigningKey;
import cn.flying.verifier.model.VerificationCheck;
import cn.flying.verifier.model.VerificationOutcome;
import cn.flying.verifier.model.VerificationReport;
import cn.flying.verifier.resolver.ChainRootResolver;
import cn.flying.verifier.resolver.HttpProofResolvers;
import cn.flying.verifier.resolver.HttpResolverConfiguration;
import cn.flying.verifier.resolver.ProofStatusResolver;
import cn.flying.verifier.resolver.Resolution;
import cn.flying.verifier.resolver.SigningKeyResolver;
import cn.flying.verifier.resolver.TrustedEvidenceLoader;

import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Executable CLI adapter for the shared public verifier SDK.
 */
public final class VerifierCli {

    public static final int EXIT_VALID = 0;
    public static final int EXIT_INVALID = 2;
    public static final int EXIT_INDETERMINATE = 3;
    public static final int EXIT_ERROR = 4;
    public static final int EXIT_USAGE = 64;

    private VerifierCli() {
    }

    /** Runs the CLI and terminates the process with the stable report exit code. */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Parses arguments, invokes the SDK once, renders the report, and returns a stable exit code.
     *
     * @param args command arguments
     * @param out standard output
     * @param err standard error
     * @return stable exit code
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            CliOptions options = CliOptions.parse(args);
            if (options.help()) {
                out.print(usage());
                return EXIT_VALID;
            }
            VerificationContext context = buildContext(options);
            VerificationReport report = new DefaultProofVerifier().verify(
                    options.originalFile(), options.proofArchive(), context);
            if ("text".equals(options.format())) {
                renderText(report, out);
            } else {
                out.println(new String(new CanonicalJson().canonicalBytes(report), StandardCharsets.UTF_8));
            }
            return exitCode(report.outcome());
        } catch (IllegalArgumentException e) {
            err.println("Usage error: " + safeMessage(e));
            err.print(usage());
            return EXIT_USAGE;
        } catch (RuntimeException e) {
            err.println("Verifier error: unable to initialize the requested verification context");
            return EXIT_ERROR;
        }
    }

    /** Builds explicit local and optional online trust resolvers from CLI options. */
    private static VerificationContext buildContext(CliOptions options) {
        HttpProofResolvers onlineResolvers = null;
        if (options.online()) {
            onlineResolvers = new HttpProofResolvers(new HttpResolverConfiguration(
                    options.issuerBaseUri(),
                    options.chainUrlTemplate(),
                    options.allowedHosts(),
                    options.allowHttp(),
                    options.allowPrivateAddresses(),
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(5),
                    256 * 1024,
                    Duration.ofMinutes(5),
                    Duration.ofSeconds(30),
                    256));
        }

        SigningKeyResolver keyResolver;
        if (options.trustedKey() != null) {
            PublicSigningKey key = TrustedEvidenceLoader.loadSigningKey(options.trustedKey());
            keyResolver = TrustedEvidenceLoader.resolver(key);
        } else if (onlineResolvers != null) {
            keyResolver = onlineResolvers;
        } else {
            keyResolver = (keyId, version) -> Resolution.unavailable("Trusted signing key resolution is disabled");
        }
        ProofStatusResolver statusResolver = onlineResolvers == null
                ? proofId -> Resolution.unavailable("Current proof status resolution is disabled")
                : onlineResolvers;
        ChainRootResolver chainResolver = onlineResolvers == null
                ? query -> Resolution.unavailable("Live chain root resolution is disabled")
                : onlineResolvers;
        VerificationLimits defaults = VerificationLimits.defaults();
        VerificationLimits limits = new VerificationLimits(
                options.maxOriginalFileBytes(),
                defaults.maxArchiveBytes(),
                defaults.maxEntryBytes(),
                defaults.maxTotalEntryBytes(),
                defaults.maxChunks(),
                defaults.maxProofNodes());
        return new VerificationContext(limits, keyResolver, statusResolver, chainResolver, Clock.systemUTC());
    }

    /** Renders the same report model as compact human-readable text. */
    private static void renderText(VerificationReport report, PrintStream out) {
        out.println("Outcome: " + report.outcome());
        out.println("Verified at: " + report.verifiedAt());
        if (report.summary() != null) {
            out.println("Proof ID: " + value(report.summary().proofId()));
            out.println("File version: " + value(report.summary().fileVersion()));
            out.println("Current status: " + value(report.summary().currentStatus()));
            out.println("Issuer trust source: " + value(report.summary().keySource()));
            out.println("Transaction: " + value(report.summary().batchTransactionHash()));
            out.println("Live block: " + value(report.summary().liveBlockNumber()));
            out.println("Contract: " + value(report.summary().contractAddress()));
            out.println("ABI fingerprint: " + value(report.summary().abiFingerprint()));
            out.println("Live chain source: " + value(report.summary().liveChainSource()));
        }
        out.println("Checks:");
        for (VerificationCheck check : report.checks()) {
            out.printf("- [%s] %s (%s): %s%n",
                    check.status(), check.id(), check.code(), check.message());
        }
    }

    /** Maps the shared outcome to the documented stable process exit code. */
    private static int exitCode(VerificationOutcome outcome) {
        return switch (outcome) {
            case VALID -> EXIT_VALID;
            case INVALID -> EXIT_INVALID;
            case INDETERMINATE -> EXIT_INDETERMINATE;
            case ERROR -> EXIT_ERROR;
        };
    }

    /** Produces a bounded safe error message without paths or raw input. */
    private static String safeMessage(IllegalArgumentException error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) {
            return "invalid arguments";
        }
        StringBuilder safe = new StringBuilder(Math.min(value.length(), 256));
        for (int index = 0; index < value.length() && safe.length() < 256; index++) {
            char character = value.charAt(index);
            safe.append(Character.isISOControl(character) ? ' ' : character);
        }
        return safe.toString().trim();
    }

    /** Renders null summary values consistently. */
    private static String value(Object value) {
        return value == null ? "not available" : String.valueOf(value);
    }

    /** Returns CLI usage and explicit online-safety semantics. */
    private static String usage() {
        return """
                RecordPlatform Public Verifier

                Usage:
                  java -jar record-platform-verifier-exec.jar verify \\
                    --file <original-file> --proof <proof.zip> [options]

                Options:
                  --trusted-key <key.json>       Explicit local trust anchor for Ed25519 verification
                  --format <json|text>           Output format (default: json)
                  --max-file-bytes <bytes>       Streaming original-file limit (default: 4294967296)
                  --online                       Explicitly enable issuer/status/live-chain HTTP resolution
                  --issuer-base-url <https-url>  Trusted RecordPlatform public endpoint origin/base path
                  --chain-url-template <url>     Trusted chain-gateway path template
                  --allow-host <host>            Exact HTTP host allowlist; repeatable
                  --allow-http                    Explicitly allow plain HTTP for local/test use
                  --allow-private-addresses      Explicitly allow private/loopback DNS targets for local/test use
                  --help                         Show this help

                Chain template placeholders:
                  {chainType} {chainId} {groupId} {contractAddress} {batchNo}

                Offline or unavailable key/status/chain resolution returns INDETERMINATE, never VALID.
                """;
    }

    /** Parsed immutable CLI options. */
    private record CliOptions(
            boolean help,
            Path originalFile,
            Path proofArchive,
            Path trustedKey,
            String format,
            long maxOriginalFileBytes,
            boolean online,
            URI issuerBaseUri,
            String chainUrlTemplate,
            Set<String> allowedHosts,
            boolean allowHttp,
            boolean allowPrivateAddresses
    ) {

        /** Parses the small explicit option grammar without a second command framework. */
        private static CliOptions parse(String[] args) {
            List<String> values = args == null ? List.of() : List.of(args);
            if (values.contains("--help") || values.contains("-h")) {
                return new CliOptions(true, null, null, null, "json",
                        VerificationLimits.defaults().maxOriginalFileBytes(), false,
                        null, null, Set.of(), false, false);
            }
            if (values.isEmpty() || !"verify".equals(values.getFirst())) {
                throw new IllegalArgumentException("the verify command is required");
            }
            Map<String, List<String>> parsed = parseOptions(values.subList(1, values.size()));
            Path file = requiredPath(parsed, "--file");
            Path proof = requiredPath(parsed, "--proof");
            Path trustedKey = optionalPath(parsed, "--trusted-key");
            String format = single(parsed, "--format", "json").toLowerCase(Locale.ROOT);
            if (!Set.of("json", "text").contains(format)) {
                throw new IllegalArgumentException("--format must be json or text");
            }
            long maxBytes = parsePositiveLong(single(parsed, "--max-file-bytes",
                    String.valueOf(VerificationLimits.defaults().maxOriginalFileBytes())), "--max-file-bytes");
            boolean online = flag(parsed, "--online");
            boolean allowHttp = flag(parsed, "--allow-http");
            boolean allowPrivate = flag(parsed, "--allow-private-addresses");
            URI issuer = optionalUri(parsed, "--issuer-base-url");
            String chainTemplate = single(parsed, "--chain-url-template", null);
            Set<String> allowedHosts = new LinkedHashSet<>(parsed.getOrDefault("--allow-host", List.of()));
            if (!online && (issuer != null || chainTemplate != null || !allowedHosts.isEmpty()
                    || allowHttp || allowPrivate)) {
                throw new IllegalArgumentException("online resolver options require --online");
            }
            if (online && (issuer == null
                    || chainTemplate == null
                    || chainTemplate.isBlank()
                    || allowedHosts.isEmpty())) {
                throw new IllegalArgumentException(
                        "--online requires --issuer-base-url, --chain-url-template, and at least one --allow-host");
            }
            return new CliOptions(false, file, proof, trustedKey, format, maxBytes, online,
                    issuer, chainTemplate, Set.copyOf(allowedHosts), allowHttp, allowPrivate);
        }

        /** Parses repeated flags and key/value options while rejecting unknown names. */
        private static Map<String, List<String>> parseOptions(List<String> args) {
            Set<String> flags = Set.of("--online", "--allow-http", "--allow-private-addresses");
            Set<String> valued = Set.of("--file", "--proof", "--trusted-key", "--format",
                    "--max-file-bytes", "--issuer-base-url", "--chain-url-template", "--allow-host");
            Map<String, List<String>> parsed = new LinkedHashMap<>();
            for (int index = 0; index < args.size(); index++) {
                String option = args.get(index);
                if (flags.contains(option)) {
                    parsed.computeIfAbsent(option, ignored -> new ArrayList<>()).add("true");
                } else if (valued.contains(option)) {
                    if (index + 1 >= args.size() || args.get(index + 1).startsWith("--")) {
                        throw new IllegalArgumentException(option + " requires a value");
                    }
                    parsed.computeIfAbsent(option, ignored -> new ArrayList<>()).add(args.get(++index));
                } else {
                    throw new IllegalArgumentException("an unknown option was supplied");
                }
            }
            return parsed;
        }

        /** Returns a required path option that appears exactly once. */
        private static Path requiredPath(Map<String, List<String>> options, String name) {
            String value = single(options, name, null);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return safePath(value, name);
        }

        /** Returns an optional path option that appears at most once. */
        private static Path optionalPath(Map<String, List<String>> options, String name) {
            String value = single(options, name, null);
            return value == null ? null : safePath(value, name);
        }

        /** Returns an optional absolute URI option. */
        private static URI optionalUri(Map<String, List<String>> options, String name) {
            String value = single(options, name, null);
            if (value == null) {
                return null;
            }
            try {
                return URI.create(value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(name + " contains an invalid URI", e);
            }
        }

        /** Creates one path while preventing platform parser diagnostics from echoing raw input. */
        private static Path safePath(String value, String name) {
            try {
                return Path.of(value);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(name + " contains an invalid path", e);
            }
        }

        /** Returns a single-valued option or its default while rejecting duplicates. */
        private static String single(Map<String, List<String>> options, String name, String defaultValue) {
            List<String> values = options.get(name);
            if (values == null || values.isEmpty()) {
                return defaultValue;
            }
            if (values.size() != 1) {
                throw new IllegalArgumentException(name + " may be supplied only once");
            }
            return values.getFirst();
        }

        /** Returns whether one flag was supplied exactly once. */
        private static boolean flag(Map<String, List<String>> options, String name) {
            List<String> values = options.get(name);
            if (values != null && values.size() > 1) {
                throw new IllegalArgumentException(name + " may be supplied only once");
            }
            return values != null;
        }

        /** Parses one strictly positive decimal long. */
        private static long parsePositiveLong(String value, String name) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed <= 0) {
                    throw new NumberFormatException("not positive");
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a positive decimal integer", e);
            }
        }
    }
}
