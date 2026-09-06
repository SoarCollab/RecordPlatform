package cn.flying.service.platform;

import cn.flying.common.constant.PlatformPermissions;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.platform.PlatformOperationLog;
import cn.flying.dao.mapper.AccountMemberAuditMapper;
import cn.flying.dao.mapper.platform.PlatformOperationMapper;
import cn.flying.service.admin.TenantMemberAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.mockito.MockedStatic;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Observable service-boundary fixture; SQL, isolation and rollback correctness require the separate MySQL IT. */
final class PlatformServiceTestSupport implements AutoCloseable {

    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final PlatformOperationMapper operations = mock(PlatformOperationMapper.class);
    final TenantMemberAuditService sanitizer = new TenantMemberAuditService(mock(AccountMemberAuditMapper.class));
    final Map<Long, PlatformOperationLog> rows = new LinkedHashMap<>();
    final List<String> events = new ArrayList<>();
    final PlatformOperationExecutor executor;
    boolean failClaim;
    boolean failSuccess;
    boolean failFailure;
    boolean hideClaim;
    boolean missNextLookup;
    private final ValidatorFactory validators = Validation.buildDefaultValidatorFactory();
    private final MockedStatic<IdUtils> ids;
    private final AtomicLong sequence = new AtomicLong(1000);

    /** Composes the real guard, validator, masker, executor and observable transaction/persistence boundaries. */
    PlatformServiceTestSupport() {
        ids = mockStatic(IdUtils.class);
        ids.when(IdUtils::nextEntityId).thenAnswer(invocation -> sequence.incrementAndGet());
        ids.when(() -> IdUtils.toExternalId(any())).thenAnswer(invocation ->
                invocation.getArgument(0) == null ? null : "E" + invocation.getArgument(0));
        ids.when(() -> IdUtils.toExternalUserId(any())).thenAnswer(invocation ->
                invocation.getArgument(0) == null ? null : "U" + invocation.getArgument(0));
        ids.when(() -> IdUtils.fromExternalId(anyString())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            if (!value.matches("[EU][0-9]+")) {
                return null;
            }
            return Long.parseLong(value.substring(1));
        });
        configureOperations();
        executor = new PlatformOperationExecutor(operations, sanitizer, json, validators.getValidator(), transactions());
        authorize("platform_admin", 0L);
    }

    /** Installs a real Spring principal and security-populated actor metadata for entry-point tests. */
    void authorize(String role, long tenantId) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        if ("platform_admin".equals(role)) {
            PlatformPermissions.allCodes().forEach(code -> authorities.add(new SimpleGrantedAuthority(code)));
        }
        User principal = new User("platform.operator", "unused", authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
        MDC.put(Const.ATTR_USER_ID, "7");
        MDC.put(Const.ATTR_USER_ROLE, role);
        TenantContext.setTenantId(tenantId);
        TenantContext.setIgnoreIsolation(false);
    }

    /** Returns the sole durable operation from small focused fixtures. */
    PlatformOperationLog row() {
        assertThat(rows).hasSize(1);
        return rows.values().iterator().next();
    }

    /** Checks a stable business result without depending on raw exception detail. */
    static void rejected(Runnable command, ResultEnum expected) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(GeneralException.class,
                failure -> assertThat(failure.getResultEnum()).isEqualTo(expected));
    }

    /** Checks forced tenant isolation at a target service or mapper boundary. */
    static void isolated(long tenantId) {
        assertThat(TenantContext.getTenantId()).isEqualTo(tenantId);
        assertThat(TenantContext.isIgnoreIsolation()).isFalse();
    }

    /** Creates detached persistence values so callback mutation cannot silently commit test state. */
    private PlatformOperationLog copy(PlatformOperationLog source) {
        return source == null ? null : json.convertValue(source, PlatformOperationLog.class);
    }

    /** Models only explicit operation-mapper outcomes and ownership, never SQL locking behavior. */
    private void configureOperations() {
        when(operations.insertClaim(any())).thenAnswer(invocation -> {
            isolated(0);
            events.add("claim");
            if (failClaim) {
                return 0;
            }
            PlatformOperationLog candidate = invocation.getArgument(0);
            if (rows.values().stream().anyMatch(row -> Objects.equals(row.getActorId(), candidate.getActorId())
                    && Objects.equals(row.getIdempotencyKey(), candidate.getIdempotencyKey()))) {
                throw new DuplicateKeyException("fixture duplicate");
            }
            rows.put(candidate.getId(), copy(candidate));
            return 1;
        });
        when(operations.selectByActorAndKey(anyLong(), anyString())).thenAnswer(invocation -> {
            isolated(0);
            if (missNextLookup) {
                missNextLookup = false;
                return null;
            }
            Long actor = invocation.getArgument(0);
            String key = invocation.getArgument(1);
            return rows.values().stream().filter(row -> Objects.equals(actor, row.getActorId())
                    && Objects.equals(key, row.getIdempotencyKey())).findFirst().map(this::copy).orElse(null);
        });
        when(operations.selectForUpdate(anyLong())).thenAnswer(invocation -> {
            isolated(0);
            events.add("lock-operation");
            return hideClaim ? null : copy(rows.get(invocation.getArgument(0)));
        });
        when(operations.selectById(anyLong())).thenAnswer(invocation -> {
            isolated(0);
            return copy(rows.get(invocation.getArgument(0)));
        });
        when(operations.completeSuccess(any())).thenAnswer(invocation -> {
            isolated(0);
            events.add("success-audit");
            if (failSuccess) {
                return 0;
            }
            PlatformOperationLog completed = invocation.getArgument(0);
            rows.put(completed.getId(), copy(completed));
            return 1;
        });
        when(operations.completeFailure(anyLong(), anyInt(), any(), anyLong())).thenAnswer(invocation -> {
            isolated(0);
            events.add("failure-audit");
            if (failFailure) {
                return 0;
            }
            PlatformOperationLog row = rows.get(invocation.getArgument(0));
            row.setStatus("FAILURE").setErrorCode(invocation.getArgument(1))
                    .setCompletedAt(invocation.getArgument(2)).setDurationMs(invocation.getArgument(3));
            return 1;
        });
    }

    /** Exposes transaction ordering while leaving physical MySQL rollback claims to integration coverage. */
    private PlatformTransactionManager transactions() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        Map<TransactionStatus, Map<Long, PlatformOperationLog>> snapshots = new IdentityHashMap<>();
        when(manager.getTransaction(any())).thenAnswer(invocation -> {
            isolated(0);
            TransactionDefinition definition = invocation.getArgument(0);
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(definition.getTimeout()).isEqualTo(30);
            events.add("begin");
            TransactionStatus status = new SimpleTransactionStatus();
            Map<Long, PlatformOperationLog> snapshot = new LinkedHashMap<>();
            rows.forEach((id, row) -> snapshot.put(id, copy(row)));
            snapshots.put(status, snapshot);
            return status;
        });
        doAnswer(invocation -> {
            isolated(0);
            events.add("commit");
            snapshots.remove(invocation.getArgument(0));
            return null;
        }).when(manager).commit(any());
        doAnswer(invocation -> {
            isolated(0);
            events.add("rollback");
            rows.clear();
            rows.putAll(snapshots.remove(invocation.getArgument(0)));
            return null;
        }).when(manager).rollback(any());
        return manager;
    }

    /** Releases thread-local state and scoped ID substitution after every fixture. */
    @Override
    public void close() {
        ids.close();
        validators.close();
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        MDC.clear();
    }
}
