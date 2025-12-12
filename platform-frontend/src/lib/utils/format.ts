/**
 * 格式化文件大小
 */
export function formatFileSize(bytes: number): string {
	if (bytes === 0) return '0 B';
	if (bytes < 0 || !Number.isFinite(bytes)) return '-';

	const units = ['B', 'KB', 'MB', 'GB', 'TB'];
	const k = 1024;
	const i = Math.min(Math.floor(Math.log(bytes) / Math.log(k)), units.length - 1);

	return `${parseFloat((bytes / Math.pow(k, i)).toFixed(2))} ${units[i]}`;
}

/**
 * 格式化数字（添加千分位）
 */
export function formatNumber(num: number): string {
	if (!Number.isFinite(num)) return '-';
	return num.toLocaleString('zh-CN');
}

/**
 * 格式化日期时间
 */
export function formatDateTime(date: string | Date, format: 'full' | 'date' | 'time' = 'full'): string {
	const d = typeof date === 'string' ? new Date(date) : date;

	if (isNaN(d.getTime())) return '-';

	const year = d.getFullYear();
	const month = String(d.getMonth() + 1).padStart(2, '0');
	const day = String(d.getDate()).padStart(2, '0');
	const hours = String(d.getHours()).padStart(2, '0');
	const minutes = String(d.getMinutes()).padStart(2, '0');
	const seconds = String(d.getSeconds()).padStart(2, '0');

	switch (format) {
		case 'date':
			return `${year}-${month}-${day}`;
		case 'time':
			return `${hours}:${minutes}:${seconds}`;
		default:
			return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`;
	}
}

/**
 * 格式化相对时间
 */
export function formatRelativeTime(date: string | Date): string {
	const d = typeof date === 'string' ? new Date(date) : date;

	if (isNaN(d.getTime())) return '-';

	const now = new Date();
	const diff = now.getTime() - d.getTime();

	// Handle future dates
	if (diff < 0) {
		const absDiff = Math.abs(diff);
		const seconds = Math.floor(absDiff / 1000);
		const minutes = Math.floor(seconds / 60);
		const hours = Math.floor(minutes / 60);
		const days = Math.floor(hours / 24);

		if (days > 0) return `${days}天后`;
		if (hours > 0) return `${hours}小时后`;
		if (minutes > 0) return `${minutes}分钟后`;
		return '即将';
	}

	const seconds = Math.floor(diff / 1000);
	const minutes = Math.floor(seconds / 60);
	const hours = Math.floor(minutes / 60);
	const days = Math.floor(hours / 24);
	const months = Math.floor(days / 30);
	const years = Math.floor(days / 365);

	if (years > 0) return `${years}年前`;
	if (months > 0) return `${months}个月前`;
	if (days > 0) return `${days}天前`;
	if (hours > 0) return `${hours}小时前`;
	if (minutes > 0) return `${minutes}分钟前`;
	return '刚刚';
}

/**
 * 格式化持续时间
 */
export function formatDuration(ms: number): string {
	const seconds = Math.floor(ms / 1000);
	const minutes = Math.floor(seconds / 60);
	const hours = Math.floor(minutes / 60);

	if (hours > 0) {
		return `${hours}小时${minutes % 60}分钟`;
	}
	if (minutes > 0) {
		return `${minutes}分钟${seconds % 60}秒`;
	}
	return `${seconds}秒`;
}

/**
 * 格式化上传速度
 */
export function formatSpeed(bytesPerSecond: number): string {
	return `${formatFileSize(bytesPerSecond)}/s`;
}

/**
 * 截断文本
 */
export function truncate(text: string, length: number, suffix = '...'): string {
	if (text.length <= length) return text;
	return text.slice(0, length - suffix.length) + suffix;
}

/**
 * 获取文件扩展名
 */
export function getFileExtension(filename: string): string {
	const lastDot = filename.lastIndexOf('.');
	return lastDot === -1 ? '' : filename.slice(lastDot + 1).toLowerCase();
}

/**
 * 获取文件图标
 */
export function getFileIcon(filename: string): string {
	const ext = getFileExtension(filename);

	const iconMap: Record<string, string> = {
		// Documents
		pdf: 'file-text',
		doc: 'file-text',
		docx: 'file-text',
		txt: 'file-text',
		md: 'file-text',

		// Spreadsheets
		xls: 'table',
		xlsx: 'table',
		csv: 'table',

		// Images
		jpg: 'image',
		jpeg: 'image',
		png: 'image',
		gif: 'image',
		svg: 'image',
		webp: 'image',

		// Videos
		mp4: 'video',
		avi: 'video',
		mov: 'video',
		mkv: 'video',

		// Audio
		mp3: 'music',
		wav: 'music',
		flac: 'music',

		// Archives
		zip: 'archive',
		rar: 'archive',
		'7z': 'archive',
		tar: 'archive',
		gz: 'archive',

		// Code
		js: 'code',
		ts: 'code',
		py: 'code',
		java: 'code',
		html: 'code',
		css: 'code',
		json: 'code'
	};

	return iconMap[ext] || 'file';
}
