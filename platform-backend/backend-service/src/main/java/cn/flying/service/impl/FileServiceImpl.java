package cn.flying.service.impl;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.util.CommonUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.service.FileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * @program: RecordPlatform
 * @description: 文件服务实现类
 * @author: flyingcoding
 * @create: 2025-03-12 21:22
 */
@Service
public class FileServiceImpl extends ServiceImpl<FileMapper, File> implements FileService {
    @DubboReference
    private BlockChainService blockChainService;

    @DubboReference
    private DistributedStorageService storageService;

    @Override
    public void prepareStoreFile(String Uid, String OriginFileName) {
        File file = new File()
                .setUid(Uid)
                .setFileName(OriginFileName)
                .setStatus(FileUploadStatus.PREPARE.getCode());
        this.saveOrUpdate(file);
    }

    @Override
    public File storeFile(String Uid, String OriginFileName, List<java.io.File> fileList,List<String> fileHashList, String fileParam) {

        if(CommonUtils.isEmpty(fileList)) return null;
        //todo 这里暂时不做失败重试，后续优化
        Result<Map<String, String>> storeedResult = storageService.storeFile(fileList, fileHashList);
        //最终得到的文件存储位置（JSON）
        String fileContent = JsonConverter.toJsonWithPretty(ResultUtils.getData(storeedResult));
        Result<String> recordResult = blockChainService.storeFile(Uid, OriginFileName, fileParam, fileContent);
        //获取存储到区块链上的文件的哈希值
        String fileHash = ResultUtils.getData(recordResult);
        if(CommonUtils.isEmpty(fileHash)) return null;
        //完成上传后更新文件元信息
        if(CommonUtils.isNotEmpty(fileHash)){
            //根据用户名及对应的文件名查找文件元信息（即要求用户所文件名不能重复）
            LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                    .eq(File::getUid, Uid)
                    .eq(File::getFileName, OriginFileName);

            File file = new File()
                .setUid(Uid)
                .setFileName(OriginFileName)
                .setFileHash(fileHash)
                .setFileParam(fileParam)
                .setStatus(FileUploadStatus.SUCCESS.getCode());

            this.update(file,wrapper);
            //返回更新后的文件元信息
            return file;
        }
        return null;
    }

    @Override
    public void deleteFile(String Uid, String fileHash) {
        if(CommonUtils.isEmpty(fileHash)) return;
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getFileHash, fileHash)
                .eq(File::getUid, Uid);
        //此处不执行实际的文件删除操作，仅更新文件元信息（实际操作使用定时任务批量执行，将文件删除或移入冷数据存储器）
        //todo 后续实现定时任务
        this.remove(wrapper);
    }

    @Override
    public List<File> getUserFiles(String Uid) {
        LambdaQueryWrapper<File> wrapper= new LambdaQueryWrapper<File>()
                .eq(File::getUid, Uid)
                .eq(File::getStatus, FileUploadStatus.SUCCESS.getCode())
                .eq(File::getStatus, FileUploadStatus.PREPARE.getCode());
        return this.list(wrapper);
    }

    @Override
    public List<String> getFileAddress(String Uid, String fileHash) {
        Result<FileDetailVO> filePointer = blockChainService.getFile(Uid, fileHash);
        FileDetailVO detailVO = ResultUtils.getData(filePointer);
        String fileContent = detailVO.getContent();
        Map<String,String> fileContentMap = JsonConverter.parse(fileContent, Map.class);
        Result<List<String>> urlListResult = storageService.getFileUrlListByHash(fileContentMap.keySet().stream().toList(), fileContentMap.values().stream().toList());
        return ResultUtils.getData(urlListResult);
    }

    @Override
    public List<java.io.File> getFile(String Uid, String fileHash) {
        Result<FileDetailVO> filePointer = blockChainService.getFile(Uid, fileHash);
        FileDetailVO detailVO = ResultUtils.getData(filePointer);
        String fileContent = detailVO.getContent();
        Map<String,String> fileContentMap = JsonConverter.parse(fileContent, Map.class);
        Result<List<java.io.File>> fileListResult = storageService.getFileListByHash(fileContentMap.keySet().stream().toList(), fileContentMap.values().stream().toList());
        return ResultUtils.getData(fileListResult);
    }
}
