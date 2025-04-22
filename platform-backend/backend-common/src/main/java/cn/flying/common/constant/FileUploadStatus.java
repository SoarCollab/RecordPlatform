package cn.flying.common.constant;

import lombok.Getter;

/**
 * @program: RecordPlatform
 * @description: 文件上传状态
 * @author: flyingcoding
 * @create: 2025-04-20 17:31
 */
@Getter
public enum FileUploadStatus {
    DELETE(2, "已完成删除"),
    SUCCESS(1, "上传成功"),
    PREPARE(0, "待上传"),
    FAIL(-1, "上传失败");

    private final int code;
    private final String message;

    FileUploadStatus(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
