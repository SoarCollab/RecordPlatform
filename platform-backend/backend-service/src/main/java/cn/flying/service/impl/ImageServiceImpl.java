package cn.flying.service.impl;

import cn.flying.common.util.Const;
import cn.flying.common.util.FlowUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.ImageStore;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.ImageStoreMapper;
import cn.flying.service.ImageService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * @program: RecordPlatform
 * @description: 文件上传相关接口实现类
 * @author flyingcoding
 * @create: 2025-01-16 14:42
 */
@Slf4j
@Service
public class ImageServiceImpl extends ServiceImpl<ImageStoreMapper, ImageStore> implements ImageService {
    @Value("${s3.bucket-name}")
    String bucketName;
    @Resource
    S3Client s3Client;
    @Resource
    AccountMapper accountMapper;
    @Resource
    FlowUtils flowUtils;
    private final SimpleDateFormat format= new SimpleDateFormat("yyyyMMdd");
    @Override
    public String uploadAvatar(MultipartFile file, Long userId) throws IOException {
        String imageName= "/avatar/"+UUID.randomUUID().toString().replace("-","");
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(imageName)
                .contentType(file.getContentType())
                .build();
        try {
            s3Client.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            String avatar =accountMapper.selectById(userId).getAvatar();
            this.deleteOldImage(avatar);
            if(accountMapper.update(null, Wrappers.<Account>update()
                    .eq("id",userId).set("avatar",imageName))>0){
                return imageName;
            }else {
                return null;
            }
        }catch (Exception e){
            log.error("图片上传失败:{}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String uploadImage(MultipartFile file, Long userId) throws IOException {
        String key= Const.IMAGE_COUNTER +userId;
        if(!flowUtils.limitPeriodCountCheck(key,20,3600))
            return null;
        String imageName= UUID.randomUUID().toString().replace("-","");
        Date data=new Date();
        imageName="/cache"+format.format(data)+"/"+imageName;
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(imageName)
                .contentType(file.getContentType())
                .build();
        try {
            s3Client.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            String imageUid = UUID.randomUUID().toString().replace("-", "");
            if (this.save(new ImageStore(imageUid, imageName, data)))
                return imageName;
            else {
                this.deleteOldImage(imageName);
                return null;
            }
        }catch (Exception e) {
            log.error("图片上传失败:{}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void fetchImage(OutputStream outputStream, String imagePath) throws Exception {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(imagePath)
                .build();
        try (ResponseInputStream<GetObjectResponse> object = s3Client.getObject(getRequest)) {
            object.transferTo(outputStream);
        }
    }
    private void deleteOldImage(String avatar){
        if (avatar==null||avatar.isEmpty())
            return ;
        DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(avatar)
                .build();
        try {
            s3Client.deleteObject(deleteRequest);
        }catch (Exception e){
            log.error("图片删除失败:{}", e.getMessage(), e);
        }
    }
}
