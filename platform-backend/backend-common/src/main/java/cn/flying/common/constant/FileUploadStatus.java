package cn.flying.common.constant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * @program: RecordPlatform
 * @description: 文件上传状态
 * @author: 王贝强
 * @create: 2025-04-20 17:31
 */
@Getter
@Schema(description = "文件上传状态")
public enum FileUploadStatus {
    DELETE(2, "已完成删除"),
    SUCCESS(1, "上传成功"),
    PREPARE(0, "待上传"),
    FAIL(-1, "上链失败"),
    NOOP(-2, "NOOP"),;

    private final int code;
    private final String message;

    FileUploadStatus(int code, String message) {
        this.code = code;
        this.message = message;
    }
    public static FileUploadStatus getByCode(int code) {
        for (FileUploadStatus status : values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        return NOOP;
    }
}
