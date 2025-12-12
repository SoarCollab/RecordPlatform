/**
 * 文件分片解密模块
 *
 * 支持解密后端加密的文件分片，实现密钥链机制。
 *
 * 加密分片格式：
 * [版本头: 4B][IV: 12B][加密数据][认证标签][--HASH--\n][hash][--NEXT_KEY--\n][key]
 *
 * 密钥链设计：
 * - chunk[i] 末尾包含 chunk[i+1] 的密钥
 * - 最后一个分片包含 chunk[0] 的密钥（环形）
 * - 解密从最后一个分片开始，使用初始密钥
 */

// 常量定义
const MAGIC_BYTES = new Uint8Array([0x52, 0x50]); // 'RP'
const VERSION = 1;
const IV_SIZE = 12;
const HEADER_SIZE = 4; // Magic(2) + Version(1) + Algorithm(1)

const ALGORITHM_AES_GCM = 0x01;
const ALGORITHM_CHACHA20 = 0x02;

const HASH_SEPARATOR = '\n--HASH--\n';
const KEY_SEPARATOR = '\n--NEXT_KEY--\n';

/**
 * 解析分片头部，获取算法信息
 */
function parseChunkHeader(data: Uint8Array): { algorithm: number; dataOffset: number } {
	if (data.length < HEADER_SIZE) {
		throw new Error('数据太短，无法解析头部');
	}

	// 检查 magic bytes
	if (data[0] !== MAGIC_BYTES[0] || data[1] !== MAGIC_BYTES[1]) {
		throw new Error('无效的文件格式：缺少 magic bytes');
	}

	const version = data[2];
	if (version !== VERSION) {
		throw new Error(`不支持的版本: ${version}`);
	}

	const algorithm = data[3];
	if (algorithm !== ALGORITHM_AES_GCM && algorithm !== ALGORITHM_CHACHA20) {
		throw new Error(`未知的加密算法: ${algorithm}`);
	}

	return { algorithm, dataOffset: HEADER_SIZE };
}

/**
 * 从加密分片中提取元数据（哈希和下一个密钥）
 */
function extractMetadata(data: Uint8Array): {
	encryptedPart: Uint8Array;
	hash: string | null;
	nextKey: string | null;
} {
	const decoder = new TextDecoder();
	const text = decoder.decode(data);

	let encryptedEndIndex = data.length;
	let hash: string | null = null;
	let nextKey: string | null = null;

	// 查找 KEY_SEPARATOR（从后往前找）
	const keyIndex = text.lastIndexOf(KEY_SEPARATOR);
	if (keyIndex !== -1) {
		nextKey = text.substring(keyIndex + KEY_SEPARATOR.length);
		encryptedEndIndex = keyIndex;
	}

	// 查找 HASH_SEPARATOR
	const hashIndex = text.lastIndexOf(HASH_SEPARATOR);
	if (hashIndex !== -1 && hashIndex < encryptedEndIndex) {
		const hashEnd = keyIndex !== -1 ? keyIndex : text.length;
		hash = text.substring(hashIndex + HASH_SEPARATOR.length, hashEnd);
		encryptedEndIndex = hashIndex;
	}

	// 找到分隔符在原始字节数组中的位置
	const encoder = new TextEncoder();
	let byteEndIndex = data.length;

	if (hashIndex !== -1) {
		// 计算到 HASH_SEPARATOR 前的字节位置
		const beforeHash = text.substring(0, hashIndex);
		byteEndIndex = encoder.encode(beforeHash).length;
	} else if (keyIndex !== -1) {
		const beforeKey = text.substring(0, keyIndex);
		byteEndIndex = encoder.encode(beforeKey).length;
	}

	return {
		encryptedPart: data.slice(0, byteEndIndex),
		hash,
		nextKey
	};
}

/**
 * AES-GCM 解密
 */
async function decryptAesGcm(
	ciphertext: Uint8Array,
	keyBytes: Uint8Array,
	iv: Uint8Array
): Promise<Uint8Array> {
	const key = await crypto.subtle.importKey(
		'raw',
		new Uint8Array(keyBytes).buffer as ArrayBuffer,
		{ name: 'AES-GCM' },
		false,
		['decrypt']
	);

	const decrypted = await crypto.subtle.decrypt(
		{ name: 'AES-GCM', iv: new Uint8Array(iv).buffer as ArrayBuffer },
		key,
		new Uint8Array(ciphertext).buffer as ArrayBuffer
	);

	return new Uint8Array(decrypted);
}

/**
 * ChaCha20-Poly1305 解密 (需要第三方库支持)
 * 当前抛出错误，提示需要安装依赖
 */
async function decryptChaCha20(
	_ciphertext: Uint8Array,
	_keyBytes: Uint8Array,
	_nonce: Uint8Array
): Promise<Uint8Array> {
	// Web Crypto API 不原生支持 ChaCha20-Poly1305
	// 需要使用 @noble/ciphers 或类似库
	throw new Error(
		'ChaCha20-Poly1305 解密需要安装 @noble/ciphers 库。请运行: pnpm add @noble/ciphers'
	);
}

/**
 * 解密单个分片
 */
async function decryptChunk(
	encryptedData: Uint8Array,
	keyBase64: string
): Promise<{ plaintext: Uint8Array; nextKey: string | null; hash: string | null }> {
	// 解码密钥
	const keyBytes = Uint8Array.from(atob(keyBase64), (c) => c.charCodeAt(0));

	if (keyBytes.length !== 32) {
		throw new Error(`无效的密钥长度: 期望 32 字节，得到 ${keyBytes.length} 字节`);
	}

	// 提取元数据（哈希和下一个密钥）
	const { encryptedPart, hash, nextKey } = extractMetadata(encryptedData);

	// 解析头部
	const { algorithm, dataOffset } = parseChunkHeader(encryptedPart);

	// 提取 IV
	if (encryptedPart.length < dataOffset + IV_SIZE) {
		throw new Error('数据太短，无法提取 IV');
	}
	const iv = encryptedPart.slice(dataOffset, dataOffset + IV_SIZE);

	// 提取密文
	const ciphertext = encryptedPart.slice(dataOffset + IV_SIZE);

	// 根据算法解密
	let plaintext: Uint8Array;
	if (algorithm === ALGORITHM_AES_GCM) {
		plaintext = await decryptAesGcm(ciphertext, keyBytes, iv);
	} else if (algorithm === ALGORITHM_CHACHA20) {
		plaintext = await decryptChaCha20(ciphertext, keyBytes, iv);
	} else {
		throw new Error(`不支持的加密算法: ${algorithm}`);
	}

	return { plaintext, nextKey, hash };
}

/**
 * 解密整个文件（所有分片）
 *
 * @param chunks - 加密的分片数组（按索引顺序：chunk_0, chunk_1, ...）
 * @param initialKey - 最后一个分片的解密密钥（Base64 编码）
 * @returns 解密后的完整文件数据
 */
export async function decryptFile(chunks: Uint8Array[], initialKey: string): Promise<Uint8Array> {
	if (chunks.length === 0) {
		throw new Error('没有分片数据');
	}

	const plaintexts: Uint8Array[] = new Array(chunks.length);
	const totalChunks = chunks.length;

	// 从最后一个分片开始解密
	let currentKey = initialKey;
	let currentIndex = totalChunks - 1;

	// 解密最后一个分片 -> 获取第一个分片的密钥
	const lastResult = await decryptChunk(chunks[currentIndex], currentKey);
	plaintexts[currentIndex] = lastResult.plaintext;

	if (!lastResult.nextKey) {
		throw new Error('最后一个分片缺少下一个密钥');
	}

	// 解密第一个分片（索引 0）
	currentKey = lastResult.nextKey;
	currentIndex = 0;

	while (currentIndex < totalChunks - 1) {
		const result = await decryptChunk(chunks[currentIndex], currentKey);
		plaintexts[currentIndex] = result.plaintext;

		if (currentIndex < totalChunks - 2 && !result.nextKey) {
			throw new Error(`分片 ${currentIndex} 缺少下一个密钥`);
		}

		if (result.nextKey) {
			currentKey = result.nextKey;
		}
		currentIndex++;
	}

	// 合并所有解密后的数据
	const totalLength = plaintexts.reduce((sum, p) => sum + p.length, 0);
	const result = new Uint8Array(totalLength);
	let offset = 0;
	for (const plaintext of plaintexts) {
		result.set(plaintext, offset);
		offset += plaintext.length;
	}

	return result;
}

/**
 * 将 Uint8Array 转换为 Blob
 */
export function arrayToBlob(data: Uint8Array, mimeType: string = 'application/octet-stream'): Blob {
	return new Blob([new Uint8Array(data).buffer as ArrayBuffer], { type: mimeType });
}

/**
 * 触发文件下载
 */
export function downloadBlob(blob: Blob, filename: string): void {
	const url = URL.createObjectURL(blob);
	const a = document.createElement('a');
	a.href = url;
	a.download = filename;
	document.body.appendChild(a);
	a.click();
	document.body.removeChild(a);
	URL.revokeObjectURL(url);
}
