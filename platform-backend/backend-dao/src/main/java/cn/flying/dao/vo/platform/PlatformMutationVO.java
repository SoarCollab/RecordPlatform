package cn.flying.dao.vo.platform;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;

/** Durable platform command outcome. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Durable platform command outcome.")
public record PlatformMutationVO(
        @Schema(description = "External operation identifier", requiredMode = Schema.RequiredMode.REQUIRED)
        String operationId,
        @Schema(description = "Typed external resource identifier or safe configuration key", requiredMode = Schema.RequiredMode.REQUIRED)
        String resourceId,
        @Schema(description = "Resource version when the command changes a versioned resource", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long version) {
}
