package cn.flying.dao.vo.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serial;
import java.io.Serializable;

/**
 * Request body for resolving an integrity alert.
 */
public record ResolveAlertVO(
        @NotBlank(message = "Resolution note is required")
        @Size(max = 512, message = "Note must not exceed 512 characters")
        String note
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
