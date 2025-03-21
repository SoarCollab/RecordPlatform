package cn.flying.platformapi.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileDetailVO {
    private String uploader;
    private String fileName;
    private String param;
    private String content;
//  private String fileHash;
    private String uploadTime;
} 