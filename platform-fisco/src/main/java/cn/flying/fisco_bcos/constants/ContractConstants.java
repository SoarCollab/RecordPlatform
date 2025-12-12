package cn.flying.fisco_bcos.constants;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Objects;
import java.nio.charset.StandardCharsets;

public class ContractConstants {

  private static final Logger log = LoggerFactory.getLogger(ContractConstants.class);

  public static String SharingAbi;

  public static String SharingBinary;

  public static String SharingGmBinary;

  public static String StorageAbi;

  public static String StorageBinary;

  public static String StorageGmBinary;

  static {
    try {
      ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

      SharingAbi = loadResource(classLoader, "abi/Sharing.abi");
      SharingBinary = loadResource(classLoader, "bin/ecc/Sharing.bin");
      SharingGmBinary = loadResource(classLoader, "bin/sm/Sharing.bin");
      StorageAbi = loadResource(classLoader, "abi/Storage.abi");
      StorageBinary = loadResource(classLoader, "bin/ecc/Storage.bin");
      StorageGmBinary = loadResource(classLoader, "bin/sm/Storage.bin");

      log.info("合约 ABI 和二进制文件加载成功");
    } catch (Exception e) {
      log.error("加载合约资源文件失败: {}", e.getMessage(), e);
      throw new ExceptionInInitializerError("Failed to load contract resources: " + e.getMessage());
    }
  }

  private static String loadResource(ClassLoader classLoader, String resourcePath) throws Exception {
    try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath)) {
      if (inputStream == null) {
        throw new IllegalStateException("Resource not found: " + resourcePath);
      }
      return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
    }
  }
}
