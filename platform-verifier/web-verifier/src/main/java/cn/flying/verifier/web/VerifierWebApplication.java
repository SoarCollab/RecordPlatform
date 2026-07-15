package cn.flying.verifier.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Standalone public Web verifier application.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class VerifierWebApplication {

    /** Starts the isolated verifier service. */
    public static void main(String[] args) {
        SpringApplication.run(VerifierWebApplication.class, args);
    }
}
