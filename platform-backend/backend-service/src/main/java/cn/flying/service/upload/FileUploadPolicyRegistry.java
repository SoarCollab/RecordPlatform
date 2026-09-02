package cn.flying.service.upload;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.vo.file.UploadFileTypePolicyVO;
import cn.flying.dao.vo.file.UploadPolicyVO;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Holds the single authoritative extension, MIME compatibility, and preview policy for uploads.
 */
public final class FileUploadPolicyRegistry {

    private static final String GENERIC_BINARY_MIME = "application/octet-stream";
    private static final String CATEGORY_SUMMARY = "文档、文本/代码、图片、音频、视频、压缩包";
    private static final Pattern CONCRETE_MIME_PATTERN = Pattern.compile(
            "^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$"
    );
    private static final Map<String, PolicyEntry> ENTRIES = createEntries();

    private FileUploadPolicyRegistry() {
    }

    /**
     * Validates that a filename has an allowed extension and that any concrete MIME matches it.
     */
    public static void validate(String fileName, String contentType) {
        String extension = extensionOf(fileName);
        if (extension == null) {
            throw new GeneralException(ResultEnum.FILE_ACCEPT_NOT_SUPPORT,
                    "文件缺少有效扩展名；可上传类型：" + CATEGORY_SUMMARY);
        }
        PolicyEntry entry = ENTRIES.get(extension);
        if (entry == null) {
            throw new GeneralException(ResultEnum.FILE_ACCEPT_NOT_SUPPORT,
                    "不支持的文件扩展名 ." + extension + "；可上传类型：" + CATEGORY_SUMMARY);
        }

        String normalizedMime = normalizeMimeType(contentType);
        if (normalizedMime.isEmpty() || GENERIC_BINARY_MIME.equals(normalizedMime)) {
            return;
        }
        if (!CONCRETE_MIME_PATTERN.matcher(normalizedMime).matches()) {
            throw new GeneralException(ResultEnum.FILE_ACCEPT_NOT_SUPPORT,
                    "文件内容类型格式无效；可上传类型：" + CATEGORY_SUMMARY);
        }
        if (!entry.mimeTypes().contains(normalizedMime)) {
            throw new GeneralException(ResultEnum.FILE_ACCEPT_NOT_SUPPORT,
                    "文件扩展名 ." + extension + " 与内容类型 " + normalizedMime
                            + " 不匹配；可上传类型：" + CATEGORY_SUMMARY);
        }
    }

    /**
     * Returns a safe stable MIME value for persistence after successful validation.
     */
    public static String normalizeForPersistence(String contentType) {
        String normalized = normalizeMimeType(contentType);
        return normalized.isEmpty() ? GENERIC_BINARY_MIME : normalized;
    }

    /**
     * Builds the authenticated policy response in stable extension order.
     */
    public static UploadPolicyVO toView(long maxFileSizeBytes) {
        List<UploadFileTypePolicyVO> fileTypes = ENTRIES.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new UploadFileTypePolicyVO(
                        entry.getKey(),
                        entry.getValue().category(),
                        entry.getValue().categoryLabel(),
                        entry.getValue().previewMode(),
                        List.copyOf(entry.getValue().mimeTypes())))
                .toList();
        return new UploadPolicyVO(maxFileSizeBytes, fileTypes);
    }

    /**
     * Normalizes MIME aliases by trimming, lowercasing, and removing parameters.
     */
    static String normalizeMimeType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int parameterIndex = contentType.indexOf(';');
        String baseType = parameterIndex >= 0 ? contentType.substring(0, parameterIndex) : contentType;
        return baseType.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Extracts a normalized extension from the final filename segment.
     */
    static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Registers all supported formats without allowing later mutation.
     */
    private static Map<String, PolicyEntry> createEntries() {
        Map<String, PolicyEntry> entries = new LinkedHashMap<>();

        register(entries, "document", "文档", "pdf", mimes("application/pdf"), "pdf");
        register(entries, "document", "文档", "unsupported", mimes("application/msword"), "doc");
        register(entries, "document", "文档", "unsupported", mimes("application/vnd.openxmlformats-officedocument.wordprocessingml.document"), "docx");
        register(entries, "document", "文档", "unsupported", mimes("application/vnd.ms-word.document.macroenabled.12"), "docm");
        register(entries, "document", "文档", "unsupported", mimes("application/vnd.ms-excel", "application/x-msexcel"), "xls");
        register(entries, "document", "文档", "unsupported", mimes("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), "xlsx");
        register(entries, "document", "文档", "unsupported", mimes("application/vnd.ms-excel.sheet.macroenabled.12"), "xlsm");
        register(entries, "document", "文档", "unsupported", mimes("application/vnd.ms-powerpoint"), "ppt");
        register(entries, "document", "文档", "unsupported", mimes("application/vnd.openxmlformats-officedocument.presentationml.presentation"), "pptx");
        register(entries, "document", "文档", "unsupported", mimes("application/vnd.ms-powerpoint.presentation.macroenabled.12"), "pptm");
        register(entries, "document", "文档", "unsupported", mimes("application/vnd.oasis.opendocument.text"), "odt");
        register(entries, "document", "文档", "unsupported", mimes("application/vnd.oasis.opendocument.spreadsheet"), "ods");
        register(entries, "document", "文档", "unsupported", mimes("application/vnd.oasis.opendocument.presentation"), "odp");
        register(entries, "document", "文档", "unsupported", mimes("application/rtf", "text/rtf"), "rtf");

        register(entries, "text", "文本/代码", "text", mimes("text/plain"), "txt", "log", "ini", "conf", "properties");
        register(entries, "text", "文本/代码", "text", mimes("text/markdown", "text/plain"), "md");
        register(entries, "text", "文本/代码", "text", mimes("text/csv", "application/csv", "text/plain"), "csv");
        register(entries, "text", "文本/代码", "text", mimes("text/tab-separated-values", "text/plain"), "tsv");
        register(entries, "text", "文本/代码", "text", mimes("application/json", "text/json", "text/plain"), "json");
        register(entries, "text", "文本/代码", "text", mimes(
                "application/json", "application/jsonl", "application/ndjson", "application/x-ndjson", "text/plain"), "jsonl");
        register(entries, "text", "文本/代码", "text", mimes("application/xml", "text/xml", "text/plain"), "xml");
        register(entries, "text", "文本/代码", "text", mimes("application/yaml", "text/yaml", "application/x-yaml", "text/plain"), "yaml", "yml");
        register(entries, "text", "文本/代码", "text", mimes("application/sql", "text/x-sql", "text/plain"), "sql");
        register(entries, "text", "文本/代码", "text", mimes("text/html", "text/plain"), "html", "htm");
        register(entries, "text", "文本/代码", "text", mimes("image/svg+xml", "text/plain"), "svg");
        register(entries, "text", "文本/代码", "text", mimes("text/css", "text/plain"), "css");
        register(entries, "text", "文本/代码", "text", mimes("application/javascript", "text/javascript", "application/x-javascript", "text/plain"), "js", "mjs", "cjs");
        register(entries, "text", "文本/代码", "text", mimes(
                "text/typescript", "application/typescript", "video/mp2t", "text/plain"), "ts", "tsx");
        register(entries, "text", "文本/代码", "text", mimes("text/jsx", "text/plain"), "jsx");
        register(entries, "text", "文本/代码", "text", mimes("text/x-java-source", "text/plain"), "java");
        register(entries, "text", "文本/代码", "text", mimes("text/x-python", "text/plain"), "py");
        register(entries, "text", "文本/代码", "text", mimes("text/x-go", "text/plain"), "go");
        register(entries, "text", "文本/代码", "text", mimes("text/x-rust", "text/plain"), "rs");
        register(entries, "text", "文本/代码", "text", mimes("text/x-c", "text/plain"), "c", "h");
        register(entries, "text", "文本/代码", "text", mimes("text/x-c++", "text/plain"), "cc", "cpp", "hpp");
        register(entries, "text", "文本/代码", "text", mimes("application/x-sh", "text/x-shellscript", "text/plain"), "sh", "bash", "zsh");
        register(entries, "text", "文本/代码", "text", mimes("text/plain", "application/x-powershell"), "ps1");

        register(entries, "image", "图片", "image", mimes("image/jpeg", "image/pjpeg"), "jpg", "jpeg");
        register(entries, "image", "图片", "image", mimes("image/png"), "png");
        register(entries, "image", "图片", "image", mimes("image/gif"), "gif");
        register(entries, "image", "图片", "image", mimes("image/webp"), "webp");
        register(entries, "image", "图片", "image", mimes("image/avif"), "avif");
        register(entries, "image", "图片", "image", mimes("image/bmp", "image/x-ms-bmp"), "bmp");
        register(entries, "image", "图片", "unsupported", mimes("image/tiff"), "tif", "tiff");
        register(entries, "image", "图片", "unsupported", mimes("image/heic", "image/heic-sequence"), "heic");
        register(entries, "image", "图片", "unsupported", mimes("image/heif", "image/heif-sequence"), "heif");

        register(entries, "audio", "音频", "audio", mimes("audio/mpeg", "audio/mp3"), "mp3");
        register(entries, "audio", "音频", "audio", mimes("audio/wav", "audio/x-wav", "audio/wave"), "wav");
        register(entries, "audio", "音频", "audio", mimes("audio/ogg"), "ogg", "oga", "opus");
        register(entries, "audio", "音频", "audio", mimes("audio/mp4", "audio/x-m4a"), "m4a");
        register(entries, "audio", "音频", "audio", mimes("audio/aac", "audio/x-aac"), "aac");
        register(entries, "audio", "音频", "audio", mimes("audio/flac", "audio/x-flac"), "flac");

        register(entries, "video", "视频", "video", mimes("video/mp4"), "mp4", "m4v");
        register(entries, "video", "视频", "video", mimes("video/webm"), "webm");
        register(entries, "video", "视频", "video", mimes("video/quicktime"), "mov");
        register(entries, "video", "视频", "video", mimes("video/x-msvideo", "video/avi"), "avi");
        register(entries, "video", "视频", "video", mimes("video/x-matroska"), "mkv");

        register(entries, "archive", "压缩包", "unsupported", mimes("application/zip", "application/x-zip-compressed"), "zip");
        register(entries, "archive", "压缩包", "unsupported", mimes("application/vnd.rar", "application/x-rar", "application/x-rar-compressed"), "rar");
        register(entries, "archive", "压缩包", "unsupported", mimes("application/x-7z-compressed"), "7z");
        register(entries, "archive", "压缩包", "unsupported", mimes("application/x-tar"), "tar");
        register(entries, "archive", "压缩包", "unsupported", mimes("application/gzip", "application/x-gzip", "application/x-compressed-tar"), "gz", "tgz");
        register(entries, "archive", "压缩包", "unsupported", mimes("application/x-bzip2"), "bz2");
        register(entries, "archive", "压缩包", "unsupported", mimes("application/x-xz"), "xz");

        return Collections.unmodifiableMap(entries);
    }

    /**
     * Adds one immutable policy entry for each extension and rejects accidental duplicates.
     */
    private static void register(
            Map<String, PolicyEntry> entries,
            String category,
            String categoryLabel,
            String previewMode,
            Set<String> mimeTypes,
            String... extensions) {
        PolicyEntry entry = new PolicyEntry(category, categoryLabel, previewMode, mimeTypes);
        for (String extension : extensions) {
            if (entries.put(extension, entry) != null) {
                throw new IllegalStateException("Duplicate upload extension policy: " + extension);
            }
        }
    }

    /**
     * Creates an immutable normalized MIME alias set.
     */
    private static Set<String> mimes(String... values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            result.add(normalizeMimeType(value));
        }
        return Collections.unmodifiableSet(result);
    }

    private record PolicyEntry(
            String category,
            String categoryLabel,
            String previewMode,
            Set<String> mimeTypes) {
    }
}
