package org.fisco.fisco_bcos.constants;

import org.apache.commons.io.IOUtils;

import java.util.Objects;
import java.nio.charset.StandardCharsets;

public class ContractConstants {
  public static String SharingAbi;

  public static String SharingBinary;

  public static String SharingGmBinary;

  public static String StorageAbi;

  public static String StorageBinary;

  public static String StorageGmBinary;

  static {
    try {
      ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

      SharingAbi = IOUtils.toString(Objects.requireNonNull(classLoader.getResourceAsStream("abi/Sharing.abi")), StandardCharsets.UTF_8);
      SharingBinary = IOUtils.toString(Objects.requireNonNull(classLoader.getResourceAsStream("bin/ecc/Sharing.bin")), StandardCharsets.UTF_8);
      SharingGmBinary = IOUtils.toString(Objects.requireNonNull(classLoader.getResourceAsStream("bin/sm/Sharing.bin")), StandardCharsets.UTF_8);
      StorageAbi = IOUtils.toString(Objects.requireNonNull(classLoader.getResourceAsStream("abi/Storage.abi")), StandardCharsets.UTF_8);
      StorageBinary = IOUtils.toString(Objects.requireNonNull(classLoader.getResourceAsStream("bin/ecc/Storage.bin")), StandardCharsets.UTF_8);
      StorageGmBinary = IOUtils.toString(Objects.requireNonNull(classLoader.getResourceAsStream("bin/sm/Storage.bin")), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
