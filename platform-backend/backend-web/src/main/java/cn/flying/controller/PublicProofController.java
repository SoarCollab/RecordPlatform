package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.annotation.RateLimit;
import cn.flying.common.constant.Result;
import cn.flying.dao.vo.file.ProofSigningKeyVO;
import cn.flying.dao.vo.file.ProofStatusVO;
import cn.flying.service.proof.signed.SignedProofArchiveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 无需 JWT 或租户头的 proof 状态与历史公钥公开只读接口。
 */
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "公开证明验证", description = "查询签名 proof 的当前状态与历史验证公钥")
@RequiredArgsConstructor
public class PublicProofController {

    private final SignedProofArchiveService signedProofArchiveService;

    /**
     * 按不可枚举 proofId 查询当前状态，禁止缓存以避免撤销状态陈旧。
     */
    @GetMapping("/proofs/{proofId}/status")
    @Operation(summary = "查询公开证明状态")
    @RateLimit(
            limit = 120,
            period = 60,
            adminLimit = 120,
            monitorLimit = 120,
            type = RateLimit.LimitType.IP,
            key = "public:proof-verification",
            tenantScoped = false,
            clientIpMode = RateLimit.ClientIpMode.TRUSTED_PEER)
    @OperationLog(module = "公开证明验证", operationType = "查询", description = "查询公开证明状态")
    public ResponseEntity<Result<ProofStatusVO>> getProofStatus(
            @Parameter(description = "proof manifest 中的公开证明标识")
            @PathVariable String proofId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .body(Result.success(signedProofArchiveService.getPublicStatus(proofId)));
    }

    /**
     * 按 key id/version 查询历史 SPKI；版本化公钥可安全短期缓存。
     */
    @GetMapping("/proof-keys/{keyId}/versions/{keyVersion}")
    @Operation(summary = "查询证明历史验证公钥")
    @RateLimit(
            limit = 120,
            period = 60,
            adminLimit = 120,
            monitorLimit = 120,
            type = RateLimit.LimitType.IP,
            key = "public:proof-verification",
            tenantScoped = false,
            clientIpMode = RateLimit.ClientIpMode.TRUSTED_PEER)
    @OperationLog(module = "公开证明验证", operationType = "查询", description = "查询证明历史验证公钥")
    public ResponseEntity<Result<ProofSigningKeyVO>> getProofSigningKey(
            @Parameter(description = "签名 key id") @PathVariable String keyId,
            @Parameter(description = "签名 key 版本") @PathVariable Integer keyVersion) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic().immutable())
                .body(Result.success(signedProofArchiveService.getPublicSigningKey(keyId, keyVersion)));
    }
}
