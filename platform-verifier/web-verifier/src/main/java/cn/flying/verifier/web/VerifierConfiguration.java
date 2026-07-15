package cn.flying.verifier.web;

import cn.flying.verifier.DefaultProofVerifier;
import cn.flying.verifier.ProofVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Explicit Spring wiring around the framework-free verification SDK.
 */
@Configuration(proxyBeanMethods = false)
public class VerifierConfiguration {

    /** Supplies the strict shared verification engine. */
    @Bean
    ProofVerifier proofVerifier() {
        return new DefaultProofVerifier();
    }

    /** Supplies one UTC clock for report timestamps, cache expiry, and request windows. */
    @Bean
    Clock verifierClock() {
        return Clock.systemUTC();
    }
}
