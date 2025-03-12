package cn.flying.backendapi.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FileSharingVO {
    private String fileName;
    private String param;
    private String content;
    private String uploadTime;
}
