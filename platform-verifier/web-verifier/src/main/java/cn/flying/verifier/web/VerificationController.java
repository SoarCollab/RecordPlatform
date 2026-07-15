package cn.flying.verifier.web;

import cn.flying.verifier.model.VerificationReport;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Multipart API boundary for the standalone public verifier.
 */
@RestController
@RequestMapping("/api/v1")
public class VerificationController {

    private final VerificationService verificationService;

    /** Creates the controller with the bounded streaming service. */
    public VerificationController(VerificationService verificationService) {
        this.verificationService = verificationService;
    }

    /** Verifies uploaded evidence and returns the shared machine-readable report unchanged. */
    @PostMapping(
            path = "/verify",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VerificationReport> verify(
            @RequestPart("original") MultipartFile original,
            @RequestPart("proof") MultipartFile proof,
            @RequestPart(value = "trustedKey", required = false) MultipartFile trustedKey
    ) {
        VerificationReport report = verificationService.verify(original, proof, trustedKey);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(report);
    }
}
