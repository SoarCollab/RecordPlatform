package cn.flying.common.constant;

import cn.flying.common.exception.GeneralException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 文件检索关键词匹配模式。
 */
public enum FileKeywordMode {
    /**
     * 模糊匹配：文件名与文件哈希均使用 LIKE %keyword%。
     */
    FUZZY,
    /**
     * 前缀匹配：文件名使用 LIKE keyword%，文件哈希使用精确匹配。
     */
    PREFIX,
    /**
     * 哈希精确匹配：仅按 file_hash 精确匹配。
     */
    EXACT_HASH,
    /**
     * 自动模式：哈希形态走 EXACT_HASH，其余走 PREFIX。
     */
    AUTO;

    private static final Pattern HEX_HASH_PATTERN = Pattern.compile("^[0-9a-fA-F]{32,128}$");

    /**
     * 解析关键词匹配模式；空值默认回落为 FUZZY。
     *
     * @param rawMode 原始模式字符串
     * @return 解析后的模式
     */
    public static FileKeywordMode parseOrDefault(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return FUZZY;
        }
        try {
            return FileKeywordMode.valueOf(rawMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID,
                    "keywordMode 仅支持 FUZZY/PREFIX/EXACT_HASH/AUTO");
        }
    }

    /**
     * 根据当前模式与关键词推导最终生效模式。
     *
     * @param keyword 搜索关键词
     * @return 生效模式
     */
    public FileKeywordMode resolveEffectiveMode(String keyword) {
        if (this != AUTO) {
            return this;
        }
        return looksLikeHashKeyword(keyword) ? EXACT_HASH : PREFIX;
    }

    /**
     * 判断关键词是否符合哈希形态（仅支持 32~128 位十六进制字符串）。
     *
     * @param keyword 搜索关键词
     * @return true 表示符合哈希形态
     */
    public static boolean looksLikeHashKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return false;
        }
        return HEX_HASH_PATTERN.matcher(keyword.trim()).matches();
    }
}
