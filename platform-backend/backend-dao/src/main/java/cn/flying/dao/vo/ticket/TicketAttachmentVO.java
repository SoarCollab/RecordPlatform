package cn.flying.dao.vo.ticket;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 工单附件 VO
 */
@Data
@Accessors(chain = true)
@Schema(description = "工单附件信息")
public class TicketAttachmentVO {

    @Schema(description = "附件ID")
    private String id;

    @Schema(description = "文件ID")
    private String fileId;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "文件大小(字节)")
    private Long fileSize;

    @Schema(description = "文件大小(可读格式)")
    private String fileSizeReadable;

    /**
     * 格式化文件大小
     */
    public TicketAttachmentVO setFileSizeWithReadable(Long fileSize) {
        this.fileSize = fileSize;
        if (fileSize != null) {
            if (fileSize < 1024) {
                this.fileSizeReadable = fileSize + " B";
            } else if (fileSize < 1024 * 1024) {
                this.fileSizeReadable = String.format("%.2f KB", fileSize / 1024.0);
            } else if (fileSize < 1024 * 1024 * 1024) {
                this.fileSizeReadable = String.format("%.2f MB", fileSize / (1024.0 * 1024));
            } else {
                this.fileSizeReadable = String.format("%.2f GB", fileSize / (1024.0 * 1024 * 1024));
            }
        }
        return this;
    }
}
