package cn.flying.service.impl;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.CommonUtils;
import cn.flying.common.util.Const;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.SharingVO;
import cn.flying.platformapi.response.TransactionVO;
import cn.flying.service.FileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * @program: RecordPlatform
 * @description: 文件服务实现类
 * @author: flyingcoding
 * @create: 2025-03-12 21:22
 */
@Service
@Transactional(rollbackFor = Exception.class)
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

        List<byte[]> fileByteList = fileList.stream().map(file -> {
            try {
                return Files.readAllBytes(file.toPath());
            } catch (IOException e) {
                throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
            }
        }).toList();

        Result<Map<String, String>> storeedResult = storageService.storeFile(fileByteList, fileHashList);
        //最终得到的文件存储位置（JSON）
        String fileContent = JsonConverter.toJsonWithPretty(ResultUtils.getData(storeedResult));
        Result<List<String>> recordResult = blockChainService.storeFile(Uid, OriginFileName, fileParam, fileContent);
        //获取存储到区块链上的文件的哈希值
        List<String> res = ResultUtils.getData(recordResult);
        //判断是不是正常返回
        if(CommonUtils.isEmpty(res)||res.size()!=2) return null;
        //交易hash
        String transactionHash = res.get(0);
        //文件hash
        String fileHash=res.get(1);
        //完成上传后更新文件元信息
        if(CommonUtils.isNotEmpty(fileHash)&&CommonUtils.isNotEmpty(transactionHash)){
            //根据用户名及对应的文件名查找文件元信息（即要求用户所文件名不能重复）
            LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                    .eq(File::getUid, Uid)
                    .eq(File::getFileName, OriginFileName);

            File file = new File()
                .setUid(Uid)
                .setFileName(OriginFileName)
                .setFileHash(fileHash)
                .setTransactionHash(transactionHash)
                .setFileParam(fileParam)
                .setStatus(FileUploadStatus.SUCCESS.getCode());

            this.update(file,wrapper);
            //返回更新后的文件元信息
            return file;
        }
        return null;
    }

    @Override
    public void changeFileStatusByName(String Uid, String fileName, Integer fileStatus) {
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getFileName, fileName)
                .eq(File::getUid, Uid);
        File file = new File()
                .setStatus(fileStatus);
        this.update(file,wrapper);
    }

    @Override
    public void changeFileStatusByHash(String Uid, String fileHash, Integer fileStatus) {
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getFileHash, fileHash)
                .eq(File::getUid, Uid);
        File file = new File()
                .setStatus(fileStatus);
        this.update(file,wrapper);
    }

    @Override
    public void deleteFile(String Uid, List<String> fileHashList) {
        if(CommonUtils.isEmpty(fileHashList)) return;
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getUid, Uid)
                .in(File::getFileHash, fileHashList);
        //此处不执行实际的文件删除操作，仅更新文件元信息（实际操作使用定时任务批量执行，将文件删除或移入冷数据存储器）
        //todo 后续实现定时任务
        this.remove(wrapper);
    }

    @Override
    public List<File> getUserFilesList(String Uid) {
        LambdaQueryWrapper<File> wrapper= new LambdaQueryWrapper<>();
        //超管账号可查看所有文件
        if(!SecurityUtils.isAdmin()){
            wrapper.eq(File::getUid, Uid);
        }

        return this.list(wrapper);
    }

    @Override
    public void getUserFilesPage(String Uid, Page<File> page) {
        LambdaQueryWrapper<File> wrapper= new LambdaQueryWrapper<>();
        //超管账号可查看所有文件
        if(!SecurityUtils.isAdmin()){
            wrapper.eq(File::getUid, Uid);
        }
        this.page(page, wrapper);
    }

    @Override
    public List<String> getFileAddress(String Uid, String fileHash) {
        Result<FileDetailVO> filePointer = blockChainService.getFile(Uid, fileHash);
        FileDetailVO detailVO = ResultUtils.getData(filePointer);
        String fileContent = detailVO.getContent();
        Map<String,String> fileContentMap = JsonConverter.parse(fileContent, Map.class);
        Result<List<String>> urlListResult = storageService.getFileUrlListByHash(fileContentMap.values().stream().toList(), fileContentMap.keySet().stream().toList());
        return ResultUtils.getData(urlListResult);
    }

    @Override
    public TransactionVO getTransactionByHash(String transactionHash) {
        Result<TransactionVO> result = blockChainService.getTransactionByHash(transactionHash);
        return ResultUtils.getData(result);
    }

    @Override
    public List<byte[]> getFile(String Uid, String fileHash) {
        Result<FileDetailVO> filePointer = blockChainService.getFile(Uid, fileHash);
        FileDetailVO detailVO = ResultUtils.getData(filePointer);
        String fileContent = detailVO.getContent();
        Map<String,String> fileContentMap = JsonConverter.parse(fileContent, Map.class);
        Result<List<byte[]>> fileListResult = storageService.getFileListByHash(fileContentMap.values().stream().toList(), fileContentMap.keySet().stream().toList());
        return ResultUtils.getData(fileListResult);
    }

    @Override
    public String generateSharingCode(String Uid, List<String> fileHash, Integer maxAccesses) {
        Result<String> result = blockChainService.shareFiles(Uid, fileHash, maxAccesses);
        return ResultUtils.getData(result);
    }

    @Override
    public List<File> getShareFile(String sharingCode) {
        Result<SharingVO> result = blockChainService.getSharedFiles(sharingCode);
        if(ResultUtils.isSuccess(result)){
            SharingVO sharingFiles = ResultUtils.getData(result);
            String uid= sharingFiles.getUploader();
            List<String> fileHashList = sharingFiles.getFileHashList();
            if(CommonUtils.isNotEmpty(fileHashList)){
                LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                        .eq(File::getUid, uid)
                        .in(File::getFileHash, fileHashList);
                return this.list(wrapper);
            }
        }
        return List.of();
    }

    @Override
    public void saveShareFile(List<String> sharingFileIdList) {
        if(CommonUtils.isNotEmpty(sharingFileIdList)){
            LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                    .in(File::getId, sharingFileIdList);
            List<File> FileList = this.list(wrapper);
            //获取当前登录用户Id
            String Uid = MDC.get(Const.ATTR_USER_ID);
            if(CommonUtils.isNotEmpty(FileList)){
                //拷贝其它用户分享文件对应的信息，修改文件所有人并增加文件来源
                FileList.forEach(file -> {
                    //如果源文件已经有来源，则保留最初的文件所有人
                    if(CommonUtils.isEmpty(file.getOrigin())){
                        file.setOrigin(file.getId());
                    }
                    //重置文件ID和创建时间
                        file
                            .setUid(Uid)
                            .setId(null)
                            .setCreateTime(null);
                });
                //批量保存文件信息
                this.saveBatch(FileList);
            }
        }
    }
}
