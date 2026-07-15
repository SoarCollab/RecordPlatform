package cn.flying.service.proof.signed;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.verifier.contract.SignedProofBundleContract;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 固定八条目、固定 metadata 的有界确定性 proof ZIP。
 */
public final class ProofArchive {

    public static final String MANIFEST_ENTRY = SignedProofBundleContract.MANIFEST_ENTRY;
    public static final String FILE_HASH_ENTRY = SignedProofBundleContract.FILE_HASH_ENTRY;
    public static final String CHUNK_MANIFEST_ENTRY = SignedProofBundleContract.CHUNK_MANIFEST_ENTRY;
    public static final String MERKLE_PROOF_ENTRY = SignedProofBundleContract.MERKLE_PROOF_ENTRY;
    public static final String BLOCKCHAIN_RECEIPT_ENTRY = SignedProofBundleContract.BLOCKCHAIN_RECEIPT_ENTRY;
    public static final String SIGNATURE_ENTRY = SignedProofBundleContract.SIGNATURE_ENTRY;
    public static final String VERIFICATION_POLICY_ENTRY = SignedProofBundleContract.VERIFICATION_POLICY_ENTRY;
    public static final String README_ENTRY = SignedProofBundleContract.README_ENTRY;

    public static final List<String> ENTRY_ORDER = SignedProofBundleContract.ENTRY_ORDER;

    private static final Set<String> ENTRY_NAMES = Set.copyOf(ENTRY_ORDER);
    // 避开 JDK 将 1980-01-01 00:00 视为 DOS 时间哨兵并写入时区相关扩展时间戳。
    private static final int MAX_ENTRY_BYTES = SignedProofBundleContract.MAX_ENTRY_BYTES;
    private static final int MAX_TOTAL_BYTES = SignedProofBundleContract.MAX_TOTAL_ENTRY_BYTES;

    private final String fileName;
    private final String manifestHash;
    private final String compactJws;
    private final List<ArchiveEntry> entries;

    /**
     * 校验固定 entry 白名单、顺序和大小上限后创建 archive。
     */
    public ProofArchive(
            String fileName,
            String manifestHash,
            String compactJws,
            List<ArchiveEntry> entries
    ) {
        if (fileName == null || !fileName.matches("^record-proof-[A-Za-z0-9_-]+-[1-9][0-9]*\\.zip$")) {
            throw invalidArchive("证明包文件名不合法");
        }
        if (entries == null || entries.size() != ENTRY_ORDER.size()) {
            throw invalidArchive("证明包条目数量不合法");
        }
        List<ArchiveEntry> copy = new ArrayList<>(entries.size());
        long totalBytes = 0L;
        for (int index = 0; index < entries.size(); index++) {
            ArchiveEntry entry = entries.get(index);
            if (entry == null
                    || !ENTRY_NAMES.contains(entry.name())
                    || !ENTRY_ORDER.get(index).equals(entry.name())
                    || entry.bytes().length > MAX_ENTRY_BYTES) {
                throw invalidArchive("证明包条目名称、顺序或大小不合法");
            }
            try {
                totalBytes = Math.addExact(totalBytes, entry.bytes().length);
            } catch (ArithmeticException e) {
                throw invalidArchive("证明包总大小溢出");
            }
            copy.add(new ArchiveEntry(entry.name(), entry.mediaType(), entry.bytes()));
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            throw invalidArchive("证明包总大小超过限制");
        }
        this.fileName = fileName;
        this.manifestHash = manifestHash;
        this.compactJws = compactJws;
        this.entries = List.copyOf(copy);
    }

    /**
     * 把 archive 以 STORED 模式直接写入调用方输出流，不关闭调用方流。
     *
     * @param outputStream HTTP 或测试输出流
     */
    public void writeTo(OutputStream outputStream) {
        if (outputStream == null) {
            throw invalidArchive("证明包输出流不能为空");
        }
        try (ZipOutputStream zip = new ZipOutputStream(new NonClosingOutputStream(outputStream))) {
            for (ArchiveEntry archiveEntry : entries) {
                byte[] bytes = archiveEntry.bytes();
                CRC32 crc = new CRC32();
                crc.update(bytes);

                ZipEntry zipEntry = new ZipEntry(archiveEntry.name());
                zipEntry.setMethod(ZipEntry.STORED);
                zipEntry.setTimeLocal(SignedProofBundleContract.FIXED_ZIP_LOCAL_TIME);
                zipEntry.setSize(bytes.length);
                zipEntry.setCompressedSize(bytes.length);
                zipEntry.setCrc(crc.getValue());
                zip.putNextEntry(zipEntry);
                zip.write(bytes);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new GeneralException(ResultEnum.FILE_DOWNLOAD_ERROR, "证明包 ZIP 写出失败");
        }
    }

    /**
     * 为确定性测试生成有界 ZIP 字节。
     *
     * @return ZIP bytes
     */
    public byte[] toByteArray() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeTo(output);
        return output.toByteArray();
    }

    /** @return 安全下载文件名。 */
    public String fileName() {
        return fileName;
    }

    /** @return canonical manifest SHA-256。 */
    public String manifestHash() {
        return manifestHash;
    }

    /** @return compact JWS。 */
    public String compactJws() {
        return compactJws;
    }

    /**
     * 返回 defensive-copy entries，避免调用方修改已签发 payload。
     */
    public List<ArchiveEntry> entries() {
        return entries.stream()
                .map(entry -> new ArchiveEntry(entry.name(), entry.mediaType(), entry.bytes()))
                .toList();
    }

    /**
     * 构造统一 archive 校验异常。
     */
    private GeneralException invalidArchive(String message) {
        return new GeneralException(ResultEnum.FILE_RECORD_ERROR, message);
    }

    /**
     * 允许关闭 ZipOutputStream 释放 Deflater，同时只刷新而不关闭调用方 HTTP 流。
     */
    private static final class NonClosingOutputStream extends FilterOutputStream {

        private NonClosingOutputStream(OutputStream outputStream) {
            super(outputStream);
        }

        /**
         * 刷新底层流但保留其生命周期给调用方管理。
         */
        @Override
        public void close() throws IOException {
            flush();
        }
    }

    /** 一个固定名称的 archive entry。 */
    public record ArchiveEntry(
            String name,
            String mediaType,
            byte[] bytes
    ) {
        public ArchiveEntry {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
