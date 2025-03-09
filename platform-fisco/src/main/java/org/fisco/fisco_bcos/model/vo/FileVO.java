package org.fisco.fisco_bcos.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileVO {
    private String fileName;
    private String fileHash;
}
