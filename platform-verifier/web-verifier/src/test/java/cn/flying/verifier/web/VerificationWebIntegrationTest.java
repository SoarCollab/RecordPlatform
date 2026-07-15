package cn.flying.verifier.web;

import cn.flying.verifier.VerifierTestFixture;
import cn.flying.verifier.crypto.CanonicalJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full Spring MVC tests for multipart verification, safe errors, static UI, and security headers.
 */
@SpringBootTest(properties = "verifier.rate-limit.requests=1000")
@AutoConfigureMockMvc
class VerificationWebIntegrationTest {

    @TempDir
    Path directory;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    Environment environment;

    private VerifierTestFixture.Fixture fixture;

    /** Creates a real proof archive consumed through the multipart HTTP boundary. */
    @BeforeEach
    void setUp() throws Exception {
        fixture = new VerifierTestFixture().create(directory);
    }

    /** Returns the shared report and stays indeterminate when server-side status/chain mode is offline. */
    @Test
    void shouldVerifyMultipartWithExplicitTrustAnchor() throws Exception {
        MockMultipartFile original = new MockMultipartFile(
                "original", "original.txt", MediaType.TEXT_PLAIN_VALUE, fixture.originalBytes());
        MockMultipartFile proof = new MockMultipartFile(
                "proof", "proof.zip", "application/zip", fixture.entries().isEmpty()
                ? new byte[0]
                : java.nio.file.Files.readAllBytes(fixture.proof()));
        MockMultipartFile key = new MockMultipartFile(
                "trustedKey", "key.json", MediaType.APPLICATION_JSON_VALUE,
                new CanonicalJson().canonicalBytes(fixture.key()));

        String response = mockMvc.perform(multipart("/api/v1/verify").file(original).file(proof).file(key))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.schemaVersion").value("record-platform-verification-report.v1"))
                .andExpect(jsonPath("$.outcome").value("INDETERMINATE"))
                .andExpect(jsonPath("$.summary.proofId").value(VerifierTestFixture.PROOF_ID))
                .andExpect(jsonPath("$.checks[?(@.code == 'SIGNATURE_VALID')]").exists())
                .andExpect(jsonPath("$.checks[?(@.code == 'STATUS_UNAVAILABLE')]").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(response)
                .doesNotContain(new String(fixture.originalBytes(), StandardCharsets.UTF_8).trim())
                .doesNotContain(fixture.key().publicKeySpki());
    }

    /** Returns a bounded public schema when a required multipart part is missing. */
    @Test
    void shouldReturnSafeMissingPartError() throws Exception {
        MockMultipartFile original = new MockMultipartFile(
                "original", "original.txt", MediaType.TEXT_PLAIN_VALUE, fixture.originalBytes());

        mockMvc.perform(multipart("/api/v1/verify").file(original))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("record-platform-verifier-error.v1"))
                .andExpect(jsonPath("$.code").value("PART_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Required multipart input is missing"));
    }

    /** Serves a self-contained UI with the same browser hardening policy as the API. */
    @Test
    void shouldServeVerifierUiWithStrictSecurityHeaders() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PUBLIC VERIFIER")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
    }

    /** Keeps servlet multipart caps aligned with the stricter application-level streaming limit. */
    @Test
    void shouldApplyBoundedMultipartDefaults() {
        assertThat(environment.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("1GB");
        assertThat(environment.getProperty("spring.servlet.multipart.max-request-size")).isEqualTo("1100MB");
        assertThat(environment.getProperty("verifier.max-original-file-bytes")).isEqualTo("1073741824");
    }
}
