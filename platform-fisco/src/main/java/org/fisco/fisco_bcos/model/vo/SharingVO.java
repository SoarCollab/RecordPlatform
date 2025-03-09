package org.fisco.fisco_bcos.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class SharingVO {
    private String uploader;
    private List<FileSharingVO> fileSharingVO;
}
