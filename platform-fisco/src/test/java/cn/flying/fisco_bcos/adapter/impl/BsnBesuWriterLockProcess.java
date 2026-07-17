package cn.flying.fisco_bcos.adapter.impl;

import cn.flying.fisco_bcos.config.BsnBesuConfig;
import org.web3j.crypto.Credentials;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 在独立 JVM 中持有 BSN Besu signer 文件锁，供跨进程门禁测试使用。
 */
public final class BsnBesuWriterLockProcess {

    private BsnBesuWriterLockProcess() {
    }

    /**
     * 获取指定 signer 的 writer 锁，写入 ready 文件后等待父进程关闭标准输入。
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Expected arguments: <stateDirectory> <chainId> <privateKey> <readyFile>"
            );
        }

        Path stateDirectory = Path.of(args[0]);
        long chainId = Long.parseLong(args[1]);
        Credentials credentials = Credentials.create(args[2]);
        Path readyFile = Path.of(args[3]);

        BsnBesuConfig config = new BsnBesuConfig();
        config.setChainId(chainId);
        config.getNonce().setStateDirectory(stateDirectory.toString());

        BsnBesuFileNonceStateStore store =
                new BsnBesuFileNonceStateStore(config, credentials);
        try {
            store.initialize();
            Files.writeString(
                    readyFile,
                    "locked\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            while (System.in.read() != -1) {
                // 父进程保持标准输入打开期间持续持有 signer 锁。
            }
        } finally {
            store.close();
        }
    }
}
