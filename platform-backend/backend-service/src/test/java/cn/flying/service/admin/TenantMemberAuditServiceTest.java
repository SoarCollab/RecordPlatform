package cn.flying.service.admin;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.mapper.AccountMemberAuditMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Verifies the mandatory, secret-free tenant member audit reason boundary. */
class TenantMemberAuditServiceTest {

    private final TenantMemberAuditService service =
            new TenantMemberAuditService(mock(AccountMemberAuditMapper.class));

    @Test
    void masksSecretLikeReasonContentBeforePersistence() {
        String sanitized = service.sanitizeReason("approved token=raw-secret password=hunter2");

        assertThat(sanitized)
                .doesNotContain("raw-secret")
                .doesNotContain("hunter2");
    }

    @Test
    void rejectsControlOnlyReasonWithStructuredParameterError() {
        assertThatThrownBy(() -> service.sanitizeReason("\u0000\u0001"))
                .isInstanceOfSatisfying(GeneralException.class,
                        error -> assertThat(error.getResultEnum()).isEqualTo(ResultEnum.PARAM_IS_INVALID));
    }
}
