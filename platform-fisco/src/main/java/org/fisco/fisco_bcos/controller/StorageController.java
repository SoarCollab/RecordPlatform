package org.fisco.fisco_bcos.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.fisco.bcos.sdk.v3.transaction.model.dto.CallResponse;
import org.fisco.bcos.sdk.v3.transaction.model.dto.TransactionResponse;
import org.fisco.fisco_bcos.model.Result;
import org.fisco.fisco_bcos.model.ResultEnum;
import org.fisco.fisco_bcos.model.bo.*;
import org.fisco.fisco_bcos.model.vo.FileDetailVO;
import org.fisco.fisco_bcos.model.vo.FileVO;
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
 * @create: 2025-01-12 19:27
 */
@Slf4j
@RestController
@RequestMapping("/api/storage")
public class StorageController {

    @Resource
    private SharingService sharingService;

    @GetMapping("/storeFile")
    public Result<String> storeFile(@RequestParam("uploader") String uploader,
                                    @RequestParam("fileName") String fileName,
                                    @RequestParam("param") String param,
                                    @RequestParam("content") String content) {
        try {
            TransactionResponse response = sharingService.storeFile(new SharingStoreFileInputBO(fileName, uploader, content, param));
            if (response != null) {
                if (response.getReturnCode() == 0) {
                    Object returnValue = response.getReturnObject();
                    if (returnValue instanceof List<?> returnList && !returnList.isEmpty()) {
                        Object firstValue = returnList.getFirst();
                        if (firstValue instanceof byte[]) {
                            String hexHash = Convert.bytesToHex((byte[]) firstValue);
                            return Result.success(hexHash.startsWith("0x") ? hexHash.substring(2) : hexHash);
                        }
                    }
                    return Result.error(ResultEnum.INVALID_RETURN_VALUE);
                }
            }
            return Result.error(ResultEnum.CONTRACT_ERROR);
        } catch (Exception e) {
            log.error("storeFile error:", e);
            return Result.error(ResultEnum.GET_USER_FILE_ERROR);
        }
    }
    @GetMapping("/getUserFiles")
    public Result<List<FileVO>> getUserFiles(@RequestParam("uploader") String uploader) {
        try {
            CallResponse response = sharingService.getUserFiles(new SharingGetUserFilesInputBO(uploader));
            List<FileVO> fileList = new ArrayList<>();
            
            if (response != null && response.getReturnObject() instanceof List<?> files) {
                Object firstElement = files.getFirst();
                if (firstElement instanceof List<?> filesList) {
                    for (Object file : filesList) {
                        if (file instanceof List<?> fileInfo && fileInfo.size() == 2) {
                            String hexHash = Convert.bytesToHex((byte[]) fileInfo.get(1));
                            hexHash = hexHash.startsWith("0x") ? hexHash.substring(2) : hexHash;
                            fileList.add(new FileVO(
                                String.valueOf(fileInfo.get(0)),
                                hexHash
                            ));
                        }
                    }
                }
            }
            
            return Result.success(fileList);
        } catch (Exception e) {
            log.error("getUserFiles error:", e);
            return Result.error(ResultEnum.GET_USER_FILE_ERROR,null);
        }
    }

    @GetMapping("/getFile")
    public Result<FileDetailVO> getFile(@RequestParam("uploader") String uploader,
                                             @RequestParam("fileHash") String fileHash) {
        try {
            CallResponse response = sharingService.getFile(new SharingGetFileInputBO(uploader, Convert.hexTobyte(fileHash)));
            if (response != null && response.getReturnObject() instanceof List<?> returnList && !returnList.isEmpty()) {
                Object returnValue = returnList.getFirst();
                if (returnValue instanceof List<?> fileInfo && fileInfo.size() == 6) {
//                    String hexHash = Convert.bytesToHex((byte[]) fileInfo.get(4));
//                    hexHash = hexHash.startsWith("0x") ? hexHash.substring(2) : hexHash;

                    // 获取时间戳
                    long uploadTimeNanos = Long.parseLong(String.valueOf(fileInfo.get(5)));
                    String formattedUploadTime = Convert.timeStampToDate(uploadTimeNanos);

                    return Result.success(new FileDetailVO(
                            // fileName
                            String.valueOf(fileInfo.get(1)),
                            // uploader
                            String.valueOf(fileInfo.get(0)),
                            // content
                            String.valueOf(fileInfo.get(3)),
                            // param
                            String.valueOf(fileInfo.get(2)),
                            // fileHash
//                          hexHash,
                            // uploadTime
                            formattedUploadTime
                    ));
                }
            }
            return Result.error(ResultEnum.GET_USER_FILE_ERROR,null);
        } catch (Exception e) {
            log.error("getFile error:", e);
            return Result.error(ResultEnum.GET_USER_FILE_ERROR,null);
        }
    }

    @GetMapping("/deleteFiles")
    public Result<Boolean> deleteFiles(@RequestParam("uploader") String uploader,
                                      @RequestParam("fileHashList") List<String> fileHashList) {
        try {
            List<byte[]> fileHashArr = new ArrayList<>();
            fileHashList.forEach(hash -> fileHashArr.add(Convert.hexTobyte(hash)));
            TransactionResponse response = sharingService.deleteFiles(new SharingDeleteFilesInputBO(uploader, fileHashArr));
            
            if (response != null && response.getReturnCode() == 0) {
                    return Result.success(true);
            }
            return Result.error(ResultEnum.GET_USER_FILE_ERROR,null);
        } catch (Exception e) {
            log.error("deleteFiles error:", e);
            return Result.error(ResultEnum.DELETE_USER_FILE_ERROR,null);
        }
    }
    @GetMapping("/deleteFile")
    public Result<Boolean> deleteFile(@RequestParam("uploader") String uploader,
                                          @RequestParam("fileHash") String fileHash) {
        try {
            TransactionResponse response =  sharingService.deleteFile(new SharingDeleteFileInputBO(uploader, Convert.hexTobyte(fileHash)));
            if (response != null && response.getReturnCode() == 0) {
                return Result.success(true);
            }
            return Result.error(ResultEnum.DELETE_USER_FILE_ERROR,null);
        } catch (Exception e) {
            log.error("deleteFiles error:", e);
            return Result.error(ResultEnum.DELETE_USER_FILE_ERROR,null);
        }
    }
}
