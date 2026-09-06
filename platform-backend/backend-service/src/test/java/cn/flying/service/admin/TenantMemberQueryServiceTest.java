package cn.flying.service.admin;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.vo.admin.TenantMemberVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Verifies bounded member queries and public mapping without claiming database isolation from substitutes. */
@ExtendWith(MockitoExtension.class)
class TenantMemberQueryServiceTest {

    @Mock private AccountMapper accountMapper;
    private MockedStatic<IdUtils> ids;
    private TenantMemberQueryService service;

    /** Makes user-ID conversion distinguishable from entity-ID conversion within each test. */
    @BeforeEach
    void setUp() {
        ids = mockStatic(IdUtils.class);
        ids.when(() -> IdUtils.toExternalUserId(anyLong()))
                .thenAnswer(invocation -> "U-member-" + invocation.getArgument(0));
        service = new TenantMemberQueryService(accountMapper);
    }

    /** Clears thread context and scoped ID stubs so test ordering cannot affect query behavior. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
        ids.close();
    }

    /** Member pages preserve metadata and row ordering while passing all filters with the exact tenant. */
    @Test
    void mapsPageAndForwardsTenantScopedFilters() {
        Account newer = account(8L, 11L);
        Account older = account(9L, 11L);
        Page<Account> page = new Page<>(2, 20, 41);
        page.setRecords(List.of(newer, older));
        when(accountMapper.selectTenantMembers(any(), eq(11L), eq("member"), eq("monitor"), eq(0)))
                .thenAnswer(invocation -> {
                    Page<Account> requested = invocation.getArgument(0);
                    assertThat(requested.getCurrent()).isEqualTo(2);
                    assertThat(requested.getSize()).isEqualTo(20);
                    return page;
                });

        IPage<TenantMemberVO> result = service.list(11L, 2, 20, "  member  ", "monitor", 0);

        assertThat(result.getCurrent()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getTotal()).isEqualTo(41);
        assertThat(result.getPages()).isEqualTo(3);
        assertThat(result.getRecords()).extracting(TenantMemberVO::id)
                .containsExactly("U-member-8", "U-member-9");
        assertView(result.getRecords().getFirst(), newer);
        ids.verify(() -> IdUtils.toExternalUserId(8L));
        ids.verify(() -> IdUtils.toExternalUserId(9L));
    }

    /** Invalid or extreme pagination inputs are bounded before reaching the mapper. */
    @ParameterizedTest
    @MethodSource("pageBounds")
    void boundsPaginationBeforeDataAccess(long requestedPage, long requestedSize,
                                          long expectedPage, long expectedSize) {
        when(accountMapper.selectTenantMembers(any(), eq(11L), isNull(), isNull(), isNull()))
                .thenAnswer(invocation -> {
                    Page<Account> page = invocation.getArgument(0);
                    assertThat(page.getCurrent()).isEqualTo(expectedPage);
                    assertThat(page.getSize()).isEqualTo(expectedSize);
                    return page;
                });

        IPage<TenantMemberVO> result = service.list(11L, requestedPage, requestedSize, null, null, null);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isZero();
        assertThat(result.getCurrent()).isEqualTo(expectedPage);
        assertThat(result.getSize()).isEqualTo(expectedSize);
    }

    /** Absent search text is normalized to no filter while retaining role and status choices. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void omitsBlankSearchFilter(String keyword) {
        when(accountMapper.selectTenantMembers(any(), eq(0L), isNull(), eq("admin"), eq(1)))
                .thenReturn(new Page<>(1, 10));

        assertThat(service.list(0L, 1, 10, keyword, "admin", 1).getRecords()).isEmpty();

        verify(accountMapper).selectTenantMembers(any(), eq(0L), isNull(), eq("admin"), eq(1));
    }

    /** Search text is trimmed and bounded without altering literal wildcard or punctuation characters. */
    @ParameterizedTest
    @MethodSource("searchBounds")
    void normalizesAndBoundsSearchText(String keyword, String expected) {
        when(accountMapper.selectTenantMembers(any(), eq(11L), eq(expected), isNull(), isNull()))
                .thenReturn(new Page<>(1, 10));

        service.list(11L, 1, 10, keyword, null, null);

        verify(accountMapper).selectTenantMembers(any(), eq(11L), eq(expected), isNull(), isNull());
    }

    /** A detail request exposes only its public fields and a user-typed external identifier. */
    @Test
    void mapsDetailWithoutPasswordOrAuthorizationVersion() {
        Account account = account(8L, 11L);
        when(accountMapper.selectTenantMember(11L, 8L)).thenReturn(account);

        TenantMemberVO view = service.get(11L, 8L);

        assertView(view, account);
        assertThat(view.toString()).doesNotContain("password-marker", "authVersion", "tenantId", "password=");
        ids.verify(() -> IdUtils.toExternalUserId(8L));
        verify(accountMapper).selectTenantMember(11L, 8L);
        verifyNoMoreInteractions(accountMapper);
    }

    /** Optional profile and login metadata remain absent instead of gaining fabricated defaults. */
    @Test
    void preservesMissingOptionalProfileFields() {
        Account account = account(8L, 11L);
        account.setNickname(null);
        account.setLastLoginTime(null);
        when(accountMapper.selectTenantMember(11L, 8L)).thenReturn(account);

        TenantMemberVO view = service.get(11L, 8L);

        assertThat(view.nickname()).isNull();
        assertThat(view.lastLoginTime()).isNull();
        assertThat(view.registerTime()).isEqualTo(account.getRegisterTime());
    }

    /** Hidden platform or other-tenant IDs use the same not-found result with no global fallback. */
    @Test
    void missingTenantMemberReturnsNonDisclosingNotFound() {
        assertThatThrownBy(() -> service.get(11L, 99L))
                .isInstanceOfSatisfying(GeneralException.class,
                        error -> assertThat(error.getResultEnum()).isEqualTo(ResultEnum.TENANT_MEMBER_NOT_FOUND));

        verify(accountMapper).selectTenantMember(11L, 99L);
        verifyNoMoreInteractions(accountMapper);
        ids.verifyNoInteractions();
    }

    /** Reusing a service never caches another tenant's result or rewrites the caller's tenant context. */
    @Test
    void everyReadUsesItsExplicitTenantAndPreservesCallerContext() {
        TenantContext.setTenantId(99L);
        when(accountMapper.selectTenantMember(11L, 8L)).thenReturn(account(8L, 11L));
        when(accountMapper.selectTenantMember(12L, 8L)).thenReturn(null);

        assertThat(service.get(11L, 8L).id()).isEqualTo("U-member-8");
        assertThatThrownBy(() -> service.get(12L, 8L)).isInstanceOf(GeneralException.class);

        verify(accountMapper).selectTenantMember(11L, 8L);
        verify(accountMapper).selectTenantMember(12L, 8L);
        assertThat(TenantContext.getTenantId()).isEqualTo(99L);
    }

    /** Persistence failures remain failures rather than appearing as a valid empty page. */
    @Test
    void propagatesQueryFailureWithoutEmptySuccessFallback() {
        IllegalStateException failure = new IllegalStateException("read unavailable");
        when(accountMapper.selectTenantMembers(any(), eq(11L), isNull(), isNull(), isNull()))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.list(11L, 1, 10, null, null, null)).isSameAs(failure);
    }

    /** Supplies minimum, maximum and out-of-range page inputs without enormous allocations. */
    private static Stream<Arguments> pageBounds() {
        return Stream.of(
                Arguments.of(0L, 0L, 1L, 1L),
                Arguments.of(-5L, -10L, 1L, 1L),
                Arguments.of(1L, 1L, 1L, 1L),
                Arguments.of(3L, 100L, 3L, 100L),
                Arguments.of(4L, 101L, 4L, 100L),
                Arguments.of(1L, Long.MAX_VALUE, 1L, 100L));
    }

    /** Supplies literal search input and the maximum allowed keyword boundary. */
    private static Stream<Arguments> searchBounds() {
        return Stream.of(
                Arguments.of("  member_%'  ", "member_%'"),
                Arguments.of("x".repeat(100), "x".repeat(100)),
                Arguments.of("  " + "x".repeat(101) + "  ", "x".repeat(100)));
    }

    /** Compares each intended public member property to its persisted source. */
    private void assertView(TenantMemberVO view, Account source) {
        assertThat(view.id()).isEqualTo("U-member-" + source.getId());
        assertThat(view.username()).isEqualTo(source.getUsername());
        assertThat(view.email()).isEqualTo(source.getEmail());
        assertThat(view.nickname()).isEqualTo(source.getNickname());
        assertThat(view.role()).isEqualTo(source.getRole());
        assertThat(view.status()).isEqualTo(source.getStatus());
        assertThat(view.registerTime()).isEqualTo(source.getRegisterTime());
        assertThat(view.lastLoginTime()).isEqualTo(source.getLastLoginTime());
    }

    /** Builds a fully populated account so accidental private-field exposure is observable. */
    private Account account(Long id, Long tenantId) {
        Account account = new Account();
        account.setId(id);
        account.setTenantId(tenantId);
        account.setUsername("member-" + id);
        account.setEmail("member-" + id + "@example.test");
        account.setNickname("Member " + id);
        account.setRole("monitor");
        account.setStatus(0);
        account.setPassword("password-marker");
        account.setAuthVersion(321L);
        account.setRegisterTime(Date.from(Instant.parse("2026-08-01T10:00:00Z")));
        account.setLastLoginTime(Date.from(Instant.parse("2026-08-02T11:00:00Z")));
        return account;
    }
}
