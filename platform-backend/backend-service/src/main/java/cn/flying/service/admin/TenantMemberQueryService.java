package cn.flying.service.admin;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.vo.admin.TenantMemberVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Tenant-isolated member read model. */
@Service
@RequiredArgsConstructor
public class TenantMemberQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private final AccountMapper accountMapper;

    /** Returns a bounded, stable page for the authenticated tenant only. */
    public IPage<TenantMemberVO> list(Long tenantId, long pageNumber, long pageSize,
                                      String keyword, String role, Integer status) {
        long boundedPage = Math.max(1, pageNumber);
        long boundedSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        IPage<Account> accounts = accountMapper.selectTenantMembers(
                new Page<>(boundedPage, boundedSize), tenantId, normalizeKeyword(keyword), role, status);
        return accounts.convert(this::toView);
    }

    /** Returns one tenant member or a non-disclosing not-found result. */
    public TenantMemberVO get(Long tenantId, Long accountId) {
        Account account = accountMapper.selectTenantMember(tenantId, accountId);
        if (account == null) {
            throw new GeneralException(ResultEnum.TENANT_MEMBER_NOT_FOUND);
        }
        return toView(account);
    }

    /** Converts an account without leaking internal IDs or authorization versions. */
    private TenantMemberVO toView(Account account) {
        return new TenantMemberVO(
                IdUtils.toExternalUserId(account.getId()),
                account.getUsername(),
                account.getEmail(),
                account.getNickname(),
                account.getRole(),
                account.getStatus(),
                account.getRegisterTime(),
                account.getLastLoginTime());
    }

    /** Trims optional search text and bounds wildcard work. */
    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String normalized = keyword.trim();
        return normalized.length() <= 100 ? normalized : normalized.substring(0, 100);
    }
}
