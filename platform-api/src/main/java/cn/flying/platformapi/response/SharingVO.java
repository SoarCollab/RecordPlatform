package cn.flying.platformapi.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class SharingVO {
    private String uploader;
    private List<String> fileHashList;
}
