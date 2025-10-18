package cn.flying.monitor.data.service.export;

/**
 * 导出下载内容包装
 */
public record ExportDownload(byte[] data, String fileName, String mimeType) {
}
