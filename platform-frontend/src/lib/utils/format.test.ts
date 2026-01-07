import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  formatFileSize,
  formatNumber,
  formatDateTime,
  formatRelativeTime,
  formatDuration,
  formatSpeed,
  truncate,
  getFileExtension,
  getFileIcon,
} from "./format";

describe("format utils", () => {
  describe("formatFileSize", () => {
    it.each([
      [0, "0 B"],
      [500, "500 B"],
      [1024, "1 KB"],
      [1536, "1.5 KB"],
      [1048576, "1 MB"],
      [1572864, "1.5 MB"],
      [1073741824, "1 GB"],
      [1099511627776, "1 TB"],
    ])("should format %d bytes as %s", (bytes, expected) => {
      expect(formatFileSize(bytes)).toBe(expected);
    });

    it("should handle string input", () => {
      expect(formatFileSize("1024")).toBe("1 KB");
      expect(formatFileSize("1048576")).toBe("1 MB");
    });

    it("should handle negative numbers", () => {
      expect(formatFileSize(-100)).toBe("-");
    });

    it("should handle NaN", () => {
      expect(formatFileSize(NaN)).toBe("-");
    });

    it("should handle Infinity", () => {
      expect(formatFileSize(Infinity)).toBe("-");
    });

    it("should handle invalid string", () => {
      expect(formatFileSize("invalid")).toBe("-");
    });
  });

  describe("formatNumber", () => {
    it.each([
      [0, "0"],
      [100, "100"],
      [1000, "1,000"],
      [1234567, "1,234,567"],
      [-1234, "-1,234"],
    ])("should format %d as %s", (num, expected) => {
      expect(formatNumber(num)).toBe(expected);
    });

    it("should handle string input", () => {
      expect(formatNumber("1234567")).toBe("1,234,567");
    });

    it("should handle NaN", () => {
      expect(formatNumber(NaN)).toBe("-");
    });

    it("should handle Infinity", () => {
      expect(formatNumber(Infinity)).toBe("-");
    });

    it("should handle invalid string", () => {
      expect(formatNumber("invalid")).toBe("-");
    });
  });

  describe("formatDateTime", () => {
    const testDate = new Date("2024-06-15T14:30:45");

    it("should format Date object with full format", () => {
      expect(formatDateTime(testDate, "full")).toBe("2024-06-15 14:30:45");
    });

    it("should format Date object with date format", () => {
      expect(formatDateTime(testDate, "date")).toBe("2024-06-15");
    });

    it("should format Date object with time format", () => {
      expect(formatDateTime(testDate, "time")).toBe("14:30:45");
    });

    it("should default to full format", () => {
      expect(formatDateTime(testDate)).toBe("2024-06-15 14:30:45");
    });

    it("should handle ISO string input", () => {
      expect(formatDateTime("2024-06-15T14:30:45", "date")).toBe("2024-06-15");
    });

    it("should handle timestamp number input", () => {
      const timestamp = testDate.getTime();
      expect(formatDateTime(timestamp, "date")).toBe("2024-06-15");
    });

    it("should handle timestamp string input", () => {
      const timestamp = String(testDate.getTime());
      expect(formatDateTime(timestamp, "date")).toBe("2024-06-15");
    });

    it("should handle invalid date", () => {
      expect(formatDateTime("invalid")).toBe("-");
    });

    it("should pad single digit values", () => {
      const date = new Date("2024-01-05T09:05:03");
      expect(formatDateTime(date, "full")).toBe("2024-01-05 09:05:03");
    });
  });

  describe("formatRelativeTime", () => {
    beforeEach(() => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date("2024-06-15T12:00:00"));
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("should return '刚刚' for recent times", () => {
      const date = new Date("2024-06-15T11:59:30");
      expect(formatRelativeTime(date)).toBe("刚刚");
    });

    it("should return minutes ago", () => {
      const date = new Date("2024-06-15T11:55:00");
      expect(formatRelativeTime(date)).toBe("5分钟前");
    });

    it("should return hours ago", () => {
      const date = new Date("2024-06-15T09:00:00");
      expect(formatRelativeTime(date)).toBe("3小时前");
    });

    it("should return days ago", () => {
      const date = new Date("2024-06-12T12:00:00");
      expect(formatRelativeTime(date)).toBe("3天前");
    });

    it("should return months ago", () => {
      const date = new Date("2024-03-15T12:00:00");
      expect(formatRelativeTime(date)).toBe("3个月前");
    });

    it("should return years ago", () => {
      const date = new Date("2022-06-15T12:00:00");
      expect(formatRelativeTime(date)).toBe("2年前");
    });

    it("should handle future dates - days", () => {
      const date = new Date("2024-06-18T12:00:00");
      expect(formatRelativeTime(date)).toBe("3天后");
    });

    it("should handle future dates - hours", () => {
      const date = new Date("2024-06-15T15:00:00");
      expect(formatRelativeTime(date)).toBe("3小时后");
    });

    it("should handle future dates - minutes", () => {
      const date = new Date("2024-06-15T12:30:00");
      expect(formatRelativeTime(date)).toBe("30分钟后");
    });

    it("should handle future dates - imminent", () => {
      const date = new Date("2024-06-15T12:00:30");
      expect(formatRelativeTime(date)).toBe("即将");
    });

    it("should handle timestamp string input", () => {
      const timestamp = String(new Date("2024-06-15T11:55:00").getTime());
      expect(formatRelativeTime(timestamp)).toBe("5分钟前");
    });

    it("should handle invalid date", () => {
      expect(formatRelativeTime("invalid")).toBe("-");
    });
  });

  describe("formatDuration", () => {
    it.each([
      [0, "0秒"],
      [1000, "1秒"],
      [30000, "30秒"],
      [60000, "1分钟0秒"],
      [90000, "1分钟30秒"],
      [3600000, "1小时0分钟"],
      [3660000, "1小时1分钟"],
      [7200000, "2小时0分钟"],
    ])("should format %d ms as %s", (ms, expected) => {
      expect(formatDuration(ms)).toBe(expected);
    });
  });

  describe("formatSpeed", () => {
    it("should format bytes per second", () => {
      expect(formatSpeed(1024)).toBe("1 KB/s");
      expect(formatSpeed(1048576)).toBe("1 MB/s");
      expect(formatSpeed(500)).toBe("500 B/s");
    });
  });

  describe("truncate", () => {
    it("should not truncate short text", () => {
      expect(truncate("hello", 10)).toBe("hello");
    });

    it("should truncate long text with default suffix", () => {
      expect(truncate("hello world", 8)).toBe("hello...");
    });

    it("should truncate with custom suffix", () => {
      expect(truncate("hello world", 8, "…")).toBe("hello w…");
    });

    it("should handle exact length", () => {
      expect(truncate("hello", 5)).toBe("hello");
    });

    it("should handle empty string", () => {
      expect(truncate("", 10)).toBe("");
    });
  });

  describe("getFileExtension", () => {
    it.each([
      ["document.pdf", "pdf"],
      ["image.PNG", "png"],
      ["file.tar.gz", "gz"],
      ["noextension", ""],
      ["file.", ""],
      [".hidden", "hidden"],
    ])('should extract extension from "%s" as "%s"', (filename, expected) => {
      expect(getFileExtension(filename)).toBe(expected);
    });
  });

  describe("getFileIcon", () => {
    it.each([
      // Documents
      ["document.pdf", "file-text"],
      ["document.doc", "file-text"],
      ["document.docx", "file-text"],
      ["document.txt", "file-text"],
      ["readme.md", "file-text"],
      // Spreadsheets
      ["data.xls", "table"],
      ["data.xlsx", "table"],
      ["data.csv", "table"],
      // Images
      ["photo.jpg", "image"],
      ["photo.jpeg", "image"],
      ["photo.png", "image"],
      ["animation.gif", "image"],
      ["vector.svg", "image"],
      ["modern.webp", "image"],
      // Videos
      ["video.mp4", "video"],
      ["video.avi", "video"],
      ["video.mov", "video"],
      ["video.mkv", "video"],
      // Audio
      ["audio.mp3", "music"],
      ["audio.wav", "music"],
      ["audio.flac", "music"],
      // Archives
      ["archive.zip", "archive"],
      ["archive.rar", "archive"],
      ["archive.7z", "archive"],
      ["archive.tar", "archive"],
      ["archive.gz", "archive"],
      // Code
      ["script.js", "code"],
      ["script.ts", "code"],
      ["script.py", "code"],
      ["Main.java", "code"],
      ["index.html", "code"],
      ["style.css", "code"],
      ["config.json", "code"],
      // Unknown
      ["unknown.xyz", "file"],
      ["noextension", "file"],
    ])('should return "%s" icon for "%s"', (filename, expected) => {
      expect(getFileIcon(filename)).toBe(expected);
    });
  });
});
