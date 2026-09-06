package cn.flying.contract;

import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.config.IdSecurityConfiguration;
import cn.flying.dao.vo.platform.PlatformAuditVO;
import cn.flying.dao.vo.platform.PlatformConfigurationVO;
import cn.flying.dao.vo.platform.PlatformHealthVO;
import cn.flying.dao.vo.platform.PlatformMutationVO;
import cn.flying.dao.vo.platform.PlatformOverviewVO;
import cn.flying.dao.vo.platform.PlatformPage;
import cn.flying.dao.vo.platform.PlatformQuotaVO;
import cn.flying.dao.vo.platform.PlatformSessionVO;
import cn.flying.dao.vo.platform.PlatformTenantVO;
import cn.flying.dao.vo.platform.PlatformUsageVO;
import cn.flying.dao.vo.platform.PlatformUserVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies platform wire contracts through the production Long-to-string mapper and restrictive null defaults. */
class PlatformWireSerializationTest {

    private static final long JS_MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 9, 6, 12, 0);
    private ObjectMapper mapper;

    /** Retains the actual global ID serializer while making nullable platform fields prove their local override. */
    @BeforeEach
    void useProductionMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder()
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .modulesToInstall(new JavaTimeModule());
        mapper = new IdSecurityConfiguration().objectMapper(builder);
    }

    /** Every business Long field emits exact JSON integers, including zero and the largest JavaScript-safe value. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("businessNumberFields")
    void serializesBusinessLongsAsExactNumbers(String name, NumberField field) throws JsonProcessingException {
        for (long value : new long[]{0L, 17L, JS_MAX_SAFE_INTEGER}) {
            JsonNode json = payload(field.create().apply(value));

            assertNumber(json, field.pointer(), value, name);
        }
    }

    /** Each individual numeric field rejects negative or unsafe values instead of emitting strings or rounded numbers. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("businessNumberFields")
    void rejectsEveryUnsafeBusinessLong(String name, NumberField field) {
        for (long value : new long[]{-1L, JS_MAX_SAFE_INTEGER + 1L, Long.MAX_VALUE}) {
            Object response = field.create().apply(value);

            JsonProcessingException failure = assertThrows(JsonProcessingException.class,
                    () -> mapper.writeValueAsString(Result.success(response)), name);

            assertFalse(failure.getMessage().contains(Long.toString(value)),
                    "The range failure must not disclose the invalid measurement");
        }
    }

    /** Valid unavailable and optional fields remain explicit JSON null even when global serialization omits nulls. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nullablePayloads")
    void retainsLegalNullFields(String name, Object response, List<String> nullableFields)
            throws JsonProcessingException {
        JsonNode json = payload(response);

        for (String field : nullableFields) {
            assertTrue(json.has(field), name + "." + field + " must be present");
            assertTrue(json.get(field).isNull(), name + "." + field + " must be JSON null");
        }
        if (response instanceof PlatformConfigurationVO) {
            assertEquals("UNAVAILABLE", json.get("state").textValue());
            assertTrue(json.get("mutable").isBoolean());
            assertTrue(json.get("restartRequired").isBoolean());
            assertFalse(json.get("restartRequired").booleanValue());
        }
    }

    /** Integer statuses and error codes remain numbers alongside the locally serialized platform Long fields. */
    @Test
    void preservesIntegerStatusesAndAuditErrorCode() throws JsonProcessingException {
        assertNumber(payload(tenant(3L, 4L)), "/status", 1L, "tenant status");
        assertNumber(payload(user()), "/status", 1L, "user status");
        JsonNode failedAudit = payload(audit(null, ResultEnum.SERVICE_UNAVAILABLE.getCode(), 5L));

        assertNumber(failedAudit, "/errorCode", ResultEnum.SERVICE_UNAVAILABLE.getCode(), "audit error code");
        assertEquals("FAILURE", failedAudit.get("status").textValue());
        assertTrue(failedAudit.get("startedAt").isTextual());
        assertTrue(failedAudit.get("completedAt").isTextual());
    }

    /** The fixed system tenant is numeric zero while opaque identity and capability strings keep their wire types. */
    @Test
    void serializesPlatformSessionWithNumericSystemIdentity() throws JsonProcessingException {
        JsonNode json = payload(new PlatformSessionVO("user-public-id", "platform-operator", "platform", 0L,
                List.of("platform:tenant:read", "platform:user:write")));

        assertNumber(json, "/systemTenantId", 0L, "system tenant");
        assertEquals("user-public-id", json.get("actorId").textValue());
        assertEquals("platform", json.get("scope").textValue());
        assertEquals(2, json.get("capabilities").size());
        assertEquals("platform:tenant:read", json.get("capabilities").get(0).textValue());
        assertEquals("platform:user:write", json.get("capabilities").get(1).textValue());
    }

    /** Health output preserves the required component map, including an empty map for an unknown measurement. */
    @Test
    void preservesHealthStatusAndRequiredComponents() throws JsonProcessingException {
        JsonNode known = payload(new PlatformHealthVO("UP", Map.of("storage", "UP")));
        JsonNode unknown = payload(new PlatformHealthVO("UNKNOWN", Map.of()));

        assertEquals("UP", known.get("status").textValue());
        assertEquals("UP", known.get("components").get("storage").textValue());
        assertEquals("UNKNOWN", unknown.get("status").textValue());
        assertTrue(unknown.has("components"));
        assertTrue(unknown.get("components").isObject());
        assertEquals(0, unknown.get("components").size());
    }

    /** Both platform page construction paths keep metadata and nested business counters numeric in a real Result body. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serializesPlatformPageMetadataAsNumbers(boolean copyTenantPage) throws JsonProcessingException {
        PlatformTenantVO tenant = tenant(3L, 4L);
        PlatformPage<PlatformTenantVO> page;
        if (copyTenantPage) {
            Page<PlatformTenantVO> source = new Page<>(2L, 2L, 5L);
            source.setRecords(List.of(tenant));
            page = PlatformPage.from(source);
        } else {
            page = new PlatformPage<>(2L, 2L, 5L);
            page.setRecords(List.of(tenant));
        }

        JsonNode json = payload(page);

        assertNumber(json, "/total", 5L, "page total");
        assertNumber(json, "/current", 2L, "current page");
        assertNumber(json, "/size", 2L, "page size");
        assertNumber(json, "/pages", 3L, "derived page count");
        assertNumber(json, "/records/0/version", 3L, "nested tenant version");
        assertNumber(json, "/records/0/memberCount", 4L, "nested member count");
        assertEquals("tenant-public-id", json.at("/records/0/id").textValue());
    }

    /** The upper safe bound remains exact for both direct metadata and the derived page count. */
    @Test
    void preservesMaximumSafePageMetadata() throws JsonProcessingException {
        JsonNode json = payload(new PlatformPage<>(JS_MAX_SAFE_INTEGER, 1L, JS_MAX_SAFE_INTEGER));

        assertNumber(json, "/total", JS_MAX_SAFE_INTEGER, "maximum total");
        assertNumber(json, "/current", JS_MAX_SAFE_INTEGER, "maximum current");
        assertNumber(json, "/size", 1L, "page size");
        assertNumber(json, "/pages", JS_MAX_SAFE_INTEGER, "maximum derived pages");
    }

    /** Invalid page counts fail before a platform response can begin serializing. */
    @ParameterizedTest
    @MethodSource("invalidPageMetadata")
    void rejectsUnsafePageMetadataAtConstruction(long current, long total) {
        GeneralException failure = assertThrows(GeneralException.class,
                () -> new PlatformPage<>(current, 1L, total));

        assertEquals(ResultEnum.SERVICE_UNAVAILABLE, failure.getResultEnum());
    }

    /** Inherited mutable page setters cannot bypass the numeric serializer's final range check. */
    @ParameterizedTest
    @ValueSource(strings = {"total", "current", "size"})
    void rejectsPageMetadataCorruptedAfterConstruction(String field) {
        PlatformPage<Object> page = new PlatformPage<>(1L, 1L, 1L);
        long unsafeValue = JS_MAX_SAFE_INTEGER + 1L;
        switch (field) {
            case "total" -> page.setTotal(unsafeValue);
            case "current" -> page.setCurrent(unsafeValue);
            case "size" -> page.setSize(unsafeValue);
            default -> throw new AssertionError("Unknown page metadata field");
        }

        assertThrows(JsonProcessingException.class, () -> mapper.writeValueAsString(Result.success(page)));
    }

    /** Platform overrides do not alter ordinary boxed/primitive Long values or ordinary tenant page metadata. */
    @Test
    void preservesOrdinaryTenantLongStringContract() throws JsonProcessingException {
        OrdinaryTenantValues values = new OrdinaryTenantValues(Long.MAX_VALUE, JS_MAX_SAFE_INTEGER + 1L, null);
        JsonNode ordinary = payload(values);
        Page<OrdinaryTenantValues> tenantPage = new Page<>(2L, 2L, 5L);
        tenantPage.setRecords(List.of(values));
        JsonNode page = payload(tenantPage);

        assertText(ordinary, "/id", Long.toString(Long.MAX_VALUE));
        assertText(ordinary, "/count", Long.toString(JS_MAX_SAFE_INTEGER + 1L));
        assertFalse(ordinary.has("optional"), "The fixture must retain the global NON_NULL policy");
        assertText(page, "/total", "5");
        assertText(page, "/current", "2");
        assertText(page, "/size", "2");
        assertText(page, "/pages", "3");
        assertText(page, "/records/0/id", Long.toString(Long.MAX_VALUE));
    }

    /** A successful member command keeps its unversioned outcome explicit when nested inside platform audit. */
    @Test
    void retainsNullMemberMutationVersionInsideAudit() throws JsonProcessingException {
        PlatformMutationVO mutation = new PlatformMutationVO("operation-public-id", "user-public-id", null);
        JsonNode json = payload(audit(mutation, null, 7L));

        assertTrue(json.at("/result").has("version"));
        assertTrue(json.at("/result/version").isNull());
        assertEquals("user-public-id", json.at("/result/resourceId").textValue());
        assertNumber(json, "/durationMs", 7L, "audit duration");
    }

    /** Lists every variable business Long field independently so one field cannot hide another field's range failure. */
    private static Stream<Arguments> businessNumberFields() {
        return Stream.of(
                number("configuration.value", "/value", v -> configuration(v, 3L, 0L, JS_MAX_SAFE_INTEGER)),
                number("configuration.version", "/version", v -> configuration(1L, v, 0L, JS_MAX_SAFE_INTEGER)),
                number("configuration.minimum", "/minimum", v -> configuration(1L, 3L, v, JS_MAX_SAFE_INTEGER)),
                number("configuration.maximum", "/maximum", v -> configuration(1L, 3L, 0L, v)),
                number("mutation.version", "/version", v -> new PlatformMutationVO("operation-public-id", "tenant-public-id", v)),
                number("overview.tenants", "/tenants", v -> new PlatformOverviewVO(v, 1L, 0L, 2L, 1L, "AVAILABLE")),
                number("overview.activeTenants", "/activeTenants", v -> new PlatformOverviewVO(1L, v, 0L, 2L, 1L, "AVAILABLE")),
                number("overview.disabledTenants", "/disabledTenants", v -> new PlatformOverviewVO(1L, 1L, v, 2L, 1L, "AVAILABLE")),
                number("overview.users", "/users", v -> new PlatformOverviewVO(1L, 1L, 0L, v, 1L, "AVAILABLE")),
                number("overview.activeUsers", "/activeUsers", v -> new PlatformOverviewVO(1L, 1L, 0L, 2L, v, "AVAILABLE")),
                number("quota.maxStorageBytes", "/maxStorageBytes", v -> quota(v, 20L, 3L, 1L, 2L)),
                number("quota.maxFileCount", "/maxFileCount", v -> quota(100L, v, 3L, 1L, 2L)),
                number("quota.usedStorageBytes", "/usedStorageBytes", v -> quota(100L, 20L, v, 1L, 2L)),
                number("quota.usedFileCount", "/usedFileCount", v -> quota(100L, 20L, 3L, v, 2L)),
                number("quota.version", "/version", v -> quota(100L, 20L, 3L, 1L, v)),
                number("tenant.version", "/version", v -> tenant(v, 4L)),
                number("tenant.memberCount", "/memberCount", v -> tenant(3L, v)),
                number("usage.users", "/users", v -> usage(v, 2L, 3L, 4L, 5L)),
                number("usage.files", "/files", v -> usage(1L, v, 3L, 4L, 5L)),
                number("usage.logicalStorageBytes", "/logicalStorageBytes", v -> usage(1L, 2L, v, 4L, 5L)),
                number("usage.auditRecords", "/auditRecords", v -> usage(1L, 2L, 3L, v, 5L)),
                number("usage.completedAttestations", "/completedAttestations", v -> usage(1L, 2L, 3L, 4L, v)),
                number("audit.durationMs", "/durationMs", v -> audit(
                        new PlatformMutationVO("operation-public-id", "tenant-public-id", 3L), null, v)))
                .map(field -> Arguments.of(field.name(), field));
    }

    /** Supplies meaningful null states for every response type that permits nullable data. */
    private static Stream<Arguments> nullablePayloads() {
        return Stream.of(
                Arguments.of("configuration unavailable value", configuration(null, 3L, 1L, 100L), List.of("value")),
                Arguments.of("configuration unavailable version", configuration(null, null, 1L, 100L), List.of("value", "version")),
                Arguments.of("unversioned member mutation", new PlatformMutationVO("operation-public-id", "user-public-id", null),
                        List.of("version")),
                Arguments.of("active tenant", tenant(3L, 4L),
                        List.of("disabledReason", "disabledAt", "disabledBy", "createTime", "updateTime")),
                Arguments.of("member without optional profile data", user(),
                        List.of("nickname", "registerTime", "lastLoginTime")),
                Arguments.of("processing audit", new PlatformAuditVO("operation-public-id", "user-public-id", "TENANT_CREATE",
                                null, "TENANT", null, "approved", null, null, "PROCESSING", null, null, null,
                                STARTED_AT, null, null),
                        List.of("targetTenantId", "resourceId", "beforeSummary", "afterSummary", "result", "errorCode",
                                "traceId", "completedAt", "durationMs")));
    }

    /** Covers invalid current-page and total-count boundaries without relying on the serializer's implementation constant. */
    private static Stream<Arguments> invalidPageMetadata() {
        return Stream.of(Arguments.of(0L, 0L), Arguments.of(JS_MAX_SAFE_INTEGER + 1L, 0L),
                Arguments.of(1L, -1L), Arguments.of(1L, JS_MAX_SAFE_INTEGER + 1L));
    }

    /** Creates an explicit field fixture without inspecting annotations or installing a replacement serializer. */
    private static NumberField number(String name, String pointer, LongFunction<Object> create) {
        return new NumberField(name, pointer, create);
    }

    /** Builds safe registry metadata with an unavailable state when the stored value cannot be projected. */
    private static PlatformConfigurationVO configuration(Long value, Long version, Long minimum, Long maximum) {
        return new PlatformConfigurationVO("HIGH_FREQ_THRESHOLD", "INTEGER", value, version, minimum, maximum,
                "High-frequency threshold", "GLOBAL", "DATABASE", true, false,
                value == null ? "UNAVAILABLE" : "AVAILABLE");
    }

    /** Supplies all tenant quota counters and version through their public record constructor. */
    private static PlatformQuotaVO quota(Long maxStorageBytes, Long maxFileCount, Long usedStorageBytes,
                                         Long usedFileCount, Long version) {
        return new PlatformQuotaVO("tenant-public-id", maxStorageBytes, maxFileCount, usedStorageBytes, usedFileCount,
                "TENANT_OVERRIDE", "ENFORCE", version);
    }

    /** Builds an active tenant whose absence of lifecycle and historical timestamps is legal on the wire. */
    private static PlatformTenantVO tenant(Long version, Long members) {
        return new PlatformTenantVO("tenant-public-id", "tenant-code", "Tenant", 1, version,
                null, null, null, null, null, members);
    }

    /** Builds member metadata with no optional nickname or historical login timestamps. */
    private static PlatformUserVO user() {
        return new PlatformUserVO("user-public-id", "tenant-public-id", "member", null, "user", 1, null, null);
    }

    /** Preserves the explicit measurement scopes while varying one usage counter at a time. */
    private static PlatformUsageVO usage(Long users, Long files, Long storage, Long audits, Long attestations) {
        return new PlatformUsageVO("tenant-public-id", users, files, storage, audits, attestations,
                "TENANT_BUSINESS", "TENANT_COMPLETED_BATCHES", "AVAILABLE");
    }

    /** Builds a completed audit result using real Java-time values and an optional nested mutation response. */
    private static PlatformAuditVO audit(PlatformMutationVO result, Integer errorCode, Long durationMs) {
        return new PlatformAuditVO("operation-public-id", "user-public-id", "TENANT_UPDATE", "tenant-public-id",
                "TENANT", "tenant-public-id", "approved", "before", "after", errorCode == null ? "SUCCESS" : "FAILURE",
                result, errorCode, "wire-trace", STARTED_AT, STARTED_AT.plusSeconds(1), durationMs);
    }

    /** Serializes the actual Result envelope and parses emitted JSON instead of inspecting Java objects. */
    private JsonNode payload(Object response) throws JsonProcessingException {
        JsonNode envelope = mapper.readTree(mapper.writeValueAsString(Result.success(response)));
        assertEquals(200, envelope.get("code").intValue());
        JsonNode data = envelope.get("data");
        assertNotNull(data);
        return data;
    }

    /** Requires an integer token and its exact decimal value, excluding quoted and floating-point representations. */
    private void assertNumber(JsonNode json, String pointer, long expected, String description) {
        JsonNode value = json.at(pointer);
        assertTrue(value.isIntegralNumber(), description + " must be a JSON integer: " + value);
        assertEquals(expected, value.longValue(), description);
        assertEquals(Long.toString(expected), value.toString(), description + " must retain the exact decimal integer");
    }

    /** Requires an ordinary tenant Long to retain its exact string representation. */
    private void assertText(JsonNode json, String pointer, String expected) {
        JsonNode value = json.at(pointer);
        assertTrue(value.isTextual(), pointer + " must retain the ordinary string contract");
        assertEquals(expected, value.textValue());
    }

    /** Couples one public JSON field to a typed fixture constructor. */
    private record NumberField(String name, String pointer, LongFunction<Object> create) {
    }

    /** Represents ordinary tenant data without any platform-specific Jackson annotations. */
    private record OrdinaryTenantValues(Long id, long count, Long optional) {
    }
}
