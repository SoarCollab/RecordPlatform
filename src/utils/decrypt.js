/**
 * 将 ArrayBuffer 转换为 Base64 字符串
 * @param {ArrayBuffer} buffer
 * @returns {string}
 */
export function arrayBufferToBase64(buffer) {
    let binary = '';
    const bytes = new Uint8Array(buffer);
    const len = bytes.byteLength;
    for (let i = 0; i < len; i++) {
        binary += String.fromCharCode(bytes[i]);
    }
    return window.btoa(binary);
}

/**
 * 将 Base64 字符串转换为 Uint8Array
 * @param {string} base64
 * @returns {Uint8Array}
 */
export function base64ToUint8Array(base64) {
    try {
        // 移除所有空白字符
        const cleanBase64 = base64.replace(/\s/g, '');
        
        // 确保字符串长度是4的倍数
        const padding = cleanBase64.length % 4;
        const paddedBase64 = padding ? cleanBase64 + '='.repeat(4 - padding) : cleanBase64;
        
        // 替换URL安全的Base64字符
        const standardBase64 = paddedBase64.replace(/-/g, '+').replace(/_/g, '/');
        
        console.log('处理后的Base64:', {
            original: base64,
            cleaned: cleanBase64,
            padded: paddedBase64,
            standard: standardBase64
        });

        const binary_string = window.atob(standardBase64);
        const len = binary_string.length;
        const bytes = new Uint8Array(len);
        for (let i = 0; i < len; i++) {
            bytes[i] = binary_string.charCodeAt(i);
        }
        return bytes;
    } catch (error) {
        console.error('Base64转换失败:', {
            input: base64,
            error: error.message
        });
        throw error;
    }
}

/**
 * 在 ArrayBuffer 中查找字节序列的位置 (从后向前)
 * @param {ArrayBuffer} buffer - 要搜索的 ArrayBuffer
 * @param {Uint8Array} sequence - 要查找的字节序列 (Uint8Array)
 * @returns {number} 序列的起始索引，如果未找到则返回 -1
 */
export function findSequenceReverse(buffer, sequence) {
    const bufferView = new Uint8Array(buffer);
    const seqLen = sequence.length;
    const bufLen = bufferView.length;

    if (seqLen === 0 || seqLen > bufLen) {
        return -1;
    }

    for (let i = bufLen - seqLen; i >= 0; i--) {
        let found = true;
        for (let j = 0; j < seqLen; j++) {
            if (bufferView[i + j] !== sequence[j]) {
                found = false;
                break;
            }
        }
        if (found) {
            return i;
        }
    }
    return -1;
}

/**
 * 比较两个 ArrayBuffer 是否相等
 * @param {ArrayBuffer} buf1
 * @param {ArrayBuffer} buf2
 * @returns {boolean}
 */
export function compareArrayBuffers(buf1, buf2) {
    // 确保输入是ArrayBuffer类型
    if (!(buf1 instanceof ArrayBuffer) || !(buf2 instanceof ArrayBuffer)) {
        console.error('比较ArrayBuffer失败：输入类型错误', {
            buf1Type: buf1?.constructor?.name,
            buf2Type: buf2?.constructor?.name
        });
        return false;
    }

    if (buf1.byteLength !== buf2.byteLength) {
        console.log('ArrayBuffer长度不匹配:', {
            buf1Length: buf1.byteLength,
            buf2Length: buf2.byteLength
        });
        return false;
    }

    const view1 = new Uint8Array(buf1);
    const view2 = new Uint8Array(buf2);
    
    for (let i = 0; i < view1.length; i++) {
        if (view1[i] !== view2[i]) {
            console.log('ArrayBuffer内容不匹配，位置:', i);
            return false;
        }
    }
    return true;
}

/**
 * 解密并解析单个文件分片
 * @param {ArrayBuffer} processedChunkData - 从后端获取的处理过的分片数据 (包含 IV, 加密内容, 哈希, 下一个密钥)
 * @param {ArrayBuffer} decryptionKeyBytes - 用于解密 *这个* 分片的原始密钥字节 (从上一个分片末尾或初始获取)
 * @returns {Promise<object>} 返回一个包含解密数据、下一个密钥CryptoKey和验证结果的对象
 */
export async function decryptAndParseChunk(processedChunkData, decryptionKeyBytes) {
    const IV_SIZE = 12; // GCM 推荐 12 字节 IV
    const HASH_SEPARATOR_STR = '\n--HASH--\n';
    const KEY_SEPARATOR_STR = '\n--NEXT_KEY--\n';
    const textEncoder = new TextEncoder();
    const textDecoder = new TextDecoder();

    const HASH_SEPARATOR_BYTES = textEncoder.encode(HASH_SEPARATOR_STR);
    const KEY_SEPARATOR_BYTES = textEncoder.encode(KEY_SEPARATOR_STR);

    let result = {
        decryptedData: null,
        nextKey: null,
        originalHashBytes: null,
        hashVerified: false,
        error: null
    };

    try {
        console.log('开始解析分片数据:', {
            totalSize: processedChunkData.byteLength,
            ivSize: IV_SIZE,
            hashSeparatorSize: HASH_SEPARATOR_BYTES.length,
            keySeparatorSize: KEY_SEPARATOR_BYTES.length
        });

        // 1. 分离各个部分
        if (processedChunkData.byteLength < IV_SIZE + HASH_SEPARATOR_BYTES.length + KEY_SEPARATOR_BYTES.length) {
            throw new Error(`分片数据过短，无法解析。实际大小: ${processedChunkData.byteLength}，最小要求: ${IV_SIZE + HASH_SEPARATOR_BYTES.length + KEY_SEPARATOR_BYTES.length}`);
        }

        const iv = processedChunkData.slice(0, IV_SIZE);
        console.log('IV:', {
            size: iv.byteLength,
            hex: Array.from(new Uint8Array(iv)).map(b => b.toString(16).padStart(2, '0')).join('')
        });

        // 从后向前查找分隔符
        const keySeparatorIndex = findSequenceReverse(processedChunkData, KEY_SEPARATOR_BYTES);
        console.log('密钥分隔符位置:', keySeparatorIndex);
        
        if (keySeparatorIndex === -1) {
            throw new Error("未找到密钥分隔符。");
        }

        const hashSeparatorIndex = findSequenceReverse(processedChunkData.slice(0, keySeparatorIndex), HASH_SEPARATOR_BYTES);
        console.log('哈希分隔符位置:', hashSeparatorIndex);
        
        if (hashSeparatorIndex === -1) {
            throw new Error("在密钥分隔符之前未找到哈希分隔符。");
        }

        const encryptedData = processedChunkData.slice(IV_SIZE, hashSeparatorIndex);
        const hashBase64Bytes = processedChunkData.slice(hashSeparatorIndex + HASH_SEPARATOR_BYTES.length, keySeparatorIndex);
        const nextKeyBase64Bytes = processedChunkData.slice(keySeparatorIndex + KEY_SEPARATOR_BYTES.length);

        console.log('分片数据解析:', {
            encryptedDataSize: encryptedData.byteLength,
            hashBase64Size: hashBase64Bytes.byteLength,
            nextKeyBase64Size: nextKeyBase64Bytes.byteLength
        });

        // 2. 解码 Base64 部分
        const hashBase64 = textDecoder.decode(hashBase64Bytes).trim();
        const nextKeyBase64 = textDecoder.decode(nextKeyBase64Bytes).trim();

        try {
            console.log('开始处理哈希和密钥...');
            result.originalHashBytes = base64ToUint8Array(hashBase64);
            const nextKeyBytes = base64ToUint8Array(nextKeyBase64);

            console.log('密钥信息:', {
                decryptionKeySize: decryptionKeyBytes.byteLength,
                nextKeySize: nextKeyBytes.byteLength
            });

            // 3. 导入解密密钥
            console.log('导入解密密钥...');
            const cryptoKey = await window.crypto.subtle.importKey(
                'raw',
                decryptionKeyBytes,
                {
                    name: 'AES-GCM',
                    length: 256 // 明确指定密钥长度
                },
                true, // 设置为可提取
                ['decrypt']
            );

            // 4. 解密数据
            console.log('开始解密数据...');
            try {
                // 确保IV是Uint8Array类型
                const ivArray = new Uint8Array(iv);
                
                result.decryptedData = await window.crypto.subtle.decrypt(
                    {
                        name: 'AES-GCM',
                        iv: ivArray,
                        tagLength: 128,
                        additionalData: new Uint8Array(0) // 添加空的additionalData
                    },
                    cryptoKey,
                    encryptedData
                );
                console.log('解密成功，数据大小:', result.decryptedData.byteLength);
            } catch (decryptError) {
                console.error('解密操作失败:', {
                    error: decryptError,
                    ivSize: iv.byteLength,
                    encryptedDataSize: encryptedData.byteLength,
                    keySize: decryptionKeyBytes.byteLength,
                    ivHex: Array.from(new Uint8Array(iv)).map(b => b.toString(16).padStart(2, '0')).join(''),
                    keyHex: Array.from(new Uint8Array(decryptionKeyBytes.slice(0, 16))).map(b => b.toString(16).padStart(2, '0')).join('')
                });
                throw new Error(`解密失败: ${decryptError.message}`);
            }

            // 5. 验证哈希
            if (result.decryptedData) {
                console.log('验证哈希...');
                const calculatedHashBuffer = await window.crypto.subtle.digest('SHA-256', result.decryptedData);
                result.hashVerified = compareArrayBuffers(calculatedHashBuffer, result.originalHashBytes.buffer);
                if (!result.hashVerified) {
                    console.warn("解密后分片的哈希值与原始哈希值不匹配！");
                }
            }

            // 6. 导入下一个密钥
            console.log('导入下一个密钥...');
            result.nextKey = await window.crypto.subtle.importKey(
                'raw',
                nextKeyBytes,
                {
                    name: 'AES-GCM',
                    length: 256 // 明确指定密钥长度
                },
                true, // 设置为可提取
                ['decrypt']
            );
            console.log('分片处理完成');

        } catch (error) {
            console.error('解密过程出错:', {
                error: error,
                message: error.message,
                stack: error.stack
            });
            throw error;
        }

    } catch (err) {
        console.error("解密或解析分片时出错:", {
            error: err,
            message: err.message,
            stack: err.stack
        });
        result.error = err.message || "未知错误";
        result.decryptedData = null;
        result.nextKey = null;
        result.hashVerified = false;
    }

    return result;
}

// --- 如何使用和拼接 ---

/**
 * 示例：解密并拼接所有分片
 * @param {Array<ArrayBuffer>} processedChunksData - 按顺序排列的所有处理后的分片数据
 * @param {ArrayBuffer} initialDecryptionKeyBytes - 解密第一个分片所需的密钥字节 (来自最后一个分片的末尾)
 * @returns {Promise<Blob | null>} 返回拼接并解密后的完整文件 Blob，如果出错则返回 null
 */
export async function decryptAndAssembleFile(processedChunksData, initialDecryptionKeyBytes) {
    if (!processedChunksData || processedChunksData.length === 0) {
        console.error("没有提供分片数据。");
        return null;
    }

    if (!initialDecryptionKeyBytes) {
        console.error("未提供初始解密密钥。");
        return null;
    }

    try {
        // 验证初始密钥格式
        if (typeof initialDecryptionKeyBytes === 'string') {
            try {
                initialDecryptionKeyBytes = base64ToUint8Array(initialDecryptionKeyBytes);
                console.log('初始密钥转换成功:', {
                    originalLength: initialDecryptionKeyBytes.length,
                    preview: Array.from(initialDecryptionKeyBytes.slice(0, 16)).map(b => b.toString(16).padStart(2, '0')).join(' ')
                });
            } catch (error) {
                console.error("初始密钥Base64解码失败:", error);
                return null;
            }
        }

        console.log("初始解密密钥信息:", {
            type: initialDecryptionKeyBytes.constructor.name,
            length: initialDecryptionKeyBytes.byteLength || initialDecryptionKeyBytes.length,
            preview: Array.from(new Uint8Array(initialDecryptionKeyBytes.slice(0, 16))).map(b => b.toString(16).padStart(2, '0')).join(' ')
        });

        const decryptedBlobs = [];
        let currentDecryptionKeyBytes = initialDecryptionKeyBytes;
        let totalSize = 0;
        let firstChunkKey = null;

        console.log(`开始解密 ${processedChunksData.length} 个分片...`);

        for (let i = 0; i < processedChunksData.length; i++) {
            console.log(`处理分片 ${i + 1}/${processedChunksData.length}...`);
            const chunkData = processedChunksData[i];

            if (!chunkData || chunkData.byteLength === 0) {
                console.error(`分片 ${i + 1} 数据无效:`, {
                    exists: !!chunkData,
                    size: chunkData?.byteLength
                });
                return null;
            }

            // 打印分片的基本信息
            console.log(`分片 ${i + 1} 信息:`, {
                size: chunkData.byteLength,
                iv: Array.from(new Uint8Array(chunkData.slice(0, 12))).map(b => b.toString(16).padStart(2, '0')).join(' '),
                keySize: currentDecryptionKeyBytes.byteLength
            });

            if (i === 0) {
                firstChunkKey = currentDecryptionKeyBytes;
            }

            const result = await decryptAndParseChunk(chunkData, currentDecryptionKeyBytes);

            if (result.error) {
                console.error(`分片 ${i + 1} 解密失败:`, {
                    error: result.error,
                    chunkSize: chunkData.byteLength,
                    keySize: currentDecryptionKeyBytes.byteLength
                });
                return null;
            }

            if (!result.decryptedData) {
                console.error(`分片 ${i + 1} 解密结果为空`);
                return null;
            }

            if (!result.nextKey) {
                console.error(`分片 ${i + 1} 未获取到下一个密钥`);
                return null;
            }

            if (!result.hashVerified) {
                console.warn(`分片 ${i + 1} 哈希校验失败，但仍继续处理。`);
            }

            decryptedBlobs.push(new Blob([result.decryptedData]));
            totalSize += result.decryptedData.byteLength;

            try {
                currentDecryptionKeyBytes = await window.crypto.subtle.exportKey('raw', result.nextKey);
                console.log(`分片 ${i + 1} 下一个密钥:`, {
                    size: currentDecryptionKeyBytes.byteLength,
                    preview: Array.from(new Uint8Array(currentDecryptionKeyBytes.slice(0, 16))).map(b => b.toString(16).padStart(2, '0')).join(' ')
                });
            } catch (error) {
                console.error(`导出分片 ${i + 1} 的下一个密钥失败:`, {
                    error: error,
                    message: error.message,
                    keyType: result.nextKey?.type,
                    keyExtractable: result.nextKey?.extractable
                });
                return null;
            }

            console.log(`分片 ${i + 1} 解密成功:`, {
                decryptedSize: result.decryptedData.byteLength,
                nextKeySize: currentDecryptionKeyBytes.byteLength,
                totalSize: totalSize
            });
        }

        // 验证密钥链
        if (firstChunkKey && currentDecryptionKeyBytes) {
            // 确保firstChunkKey是ArrayBuffer类型
            const firstKeyBuffer = firstChunkKey instanceof ArrayBuffer ? 
                firstChunkKey : 
                new Uint8Array(firstChunkKey).buffer;
            
            // 确保currentDecryptionKeyBytes是ArrayBuffer类型
            const lastKeyBuffer = currentDecryptionKeyBytes instanceof ArrayBuffer ? 
                currentDecryptionKeyBytes : 
                new Uint8Array(currentDecryptionKeyBytes).buffer;

            const keysMatch = compareArrayBuffers(firstKeyBuffer, lastKeyBuffer);
            console.log("密钥链验证:", {
                keysMatch,
                firstKeySize: firstKeyBuffer.byteLength,
                lastKeySize: lastKeyBuffer.byteLength,
                firstKeyPreview: Array.from(new Uint8Array(firstKeyBuffer.slice(0, 16))).map(b => b.toString(16).padStart(2, '0')).join(' '),
                lastKeyPreview: Array.from(new Uint8Array(lastKeyBuffer.slice(0, 16))).map(b => b.toString(16).padStart(2, '0')).join(' ')
            });

            if (!keysMatch) {
                console.error("密钥链验证失败：最后一个分片提取的下一个密钥与第一个分片的密钥不匹配！");
                return null;
            }
        }

        console.log(`所有分片解密完成，总大小: ${totalSize} 字节。开始拼接...`);

        const finalBlob = new Blob(decryptedBlobs);
        console.log("文件拼接完成:", {
            finalSize: finalBlob.size,
            chunkCount: decryptedBlobs.length
        });

        return finalBlob;
    } catch (error) {
        console.error("文件解密和组装过程中发生错误:", {
            error: error,
            message: error.message,
            stack: error.stack
        });
        return null;
    }
}

// ... existing code ...