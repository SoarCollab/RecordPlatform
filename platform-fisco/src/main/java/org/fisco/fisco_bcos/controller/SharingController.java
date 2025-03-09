package org.fisco.fisco_bcos.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.fisco.bcos.sdk.v3.transaction.model.dto.TransactionResponse;
import org.fisco.fisco_bcos.model.Result;
import org.fisco.fisco_bcos.model.ResultEnum;
import org.fisco.fisco_bcos.model.bo.SharingGetSharedFilesInputBO;
import org.fisco.fisco_bcos.model.bo.SharingShareFilesInputBO;
import org.fisco.fisco_bcos.model.vo.FileSharingVO;
import org.fisco.fisco_bcos.model.vo.SharingVO;
import org.fisco.fisco_bcos.service.SharingService;

import org.fisco.fisco_bcos.utils.Convert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.util.ArrayList;
import java.util.List;

/**
 * @program: fisco_bcos
 * @description:
 * @author: flyingcoding
 * @create: 2025-01-12 19:28
 */
@Slf4j
@RestController
@RequestMapping("/api/sharing")
public class SharingController {
    @Resource
    private SharingService sharingService;

    @GetMapping("/shareFiles")
    public Result<String> shareFiles(@RequestParam("uploader") String uploader,
                                     @RequestParam("fileHashList") List<String> fileHash,
                                     @RequestParam("maxAccesses") Integer maxAccesses) {
        try {
            List<byte[]> fileHashArr = new ArrayList<>();
            fileHash.forEach(hash -> fileHashArr.add(Convert.hexTobyte(hash)));
            TransactionResponse response = sharingService.shareFiles(
                new SharingShareFilesInputBO(uploader, fileHashArr, maxAccesses)
            );
            
            if (response != null) {
                if (response.getReturnCode() == 0) {
                    Object returnValue = response.getReturnObject();
                    if (returnValue instanceof List<?> returnList && !returnList.isEmpty()) {
                        String shareCode = String.valueOf(returnList.getFirst());
                        return Result.success(shareCode);
                    }
                    return Result.error("Invalid return value format");
                } else {
                    return Result.error(response.getReturnMessage());
                }
            }
            return Result.error("NETWORK ERROR");
        } catch (Exception e) {
            log.error("shareFiles error:", e);
            return Result.error(e.getMessage());
        }
    }
    @GetMapping("/getSharedFiles")
    public Result<SharingVO> getSharedFiles(@RequestParam("shareCode") String shareCode) {
        try {
            TransactionResponse response = sharingService.getSharedFiles(new SharingGetSharedFilesInputBO(shareCode));
            if (response != null) {
                // 检查合约执行是否成功
                if (response.getReturnCode() != 0) {
                    // 返回合约的错误信息
                    return Result.error(ResultEnum.FAIL,null);
                }
                
                if (response.getReturnObject() instanceof List<?> returnList) {
                    if (returnList.size() == 2) {
                        String uploader = String.valueOf(returnList.getFirst());
                        List<FileSharingVO> fileList = new ArrayList<>();
                        
                        // 处理文件列表
                        if (returnList.getLast() instanceof List<?> files) {
                            for (Object file : files) {
                                if (file instanceof List<?> fileInfo && fileInfo.size() == 6) {
                                    fileList.add(new FileSharingVO(
                                            // fileName
                                            String.valueOf(fileInfo.getFirst()),
                                            // fileParam
                                            String.valueOf(fileInfo.get(3)),
                                            // fileContent
                                            String.valueOf(fileInfo.get(2)),
                                            // fileUploadTime
                                        Convert.timeStampToDate(Long.parseLong(String.valueOf(fileInfo.getLast())))
                                    ));
                                }
                            }
                        }

                        return Result.success(new SharingVO(
                            uploader,
                            fileList
                        ));
                    }
                    return Result.error(ResultEnum.INVALID_RETURN_VALUE,null);
                }
                return Result.error(ResultEnum.INVALID_RETURN_VALUE,null);
            }
            return Result.error(ResultEnum.INVALID_RETURN_VALUE,null);
        } catch (Exception e) {
            log.error("getSharedFiles error:", e);
            return Result.error(ResultEnum.GET_USER_SHARE_FILE_ERROR,null);
        }
    }

}
