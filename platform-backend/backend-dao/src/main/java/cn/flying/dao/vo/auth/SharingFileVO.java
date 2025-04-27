package cn.flying.dao.vo.auth;

import cn.flying.dao.dto.File;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * @program: RecordPlatform
 * @description:
 * @author: flyingcoding
 * @create: 2025-04-27 03:13
 */
@Getter
@Setter
public class SharingFileVO {
    private String sharingUserid;
    private String sharingUsername;
    private List<File> fileList;

}
