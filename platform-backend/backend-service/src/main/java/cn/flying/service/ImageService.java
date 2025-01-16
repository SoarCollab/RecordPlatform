package cn.flying.service;

import cn.flying.dao.dto.ImageStore;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;

/**
 * @program: RecordPlatform
 * @description: 文件上传相关接口
 * @author: flyingcoding
 * @create: 2025-01-16 14:40
 */

public interface ImageService extends IService<ImageStore> {
    String uploadAvatar(MultipartFile file,String userId) throws IOException;
    String uploadImage(MultipartFile file,String userId) throws IOException;
    void fetchImage(OutputStream outputStream,String imagePath) throws Exception;
}
