// chunked-uploader.js
import { ConcurrentQueue } from './ConcurrentQueue.js';

export class ChunkedUploader {
  /**
   * 分片上传器（预分片增强版）
   * @param {Object} options 配置选项
   * @param {File} options.file 要上传的文件
   * @param {number} [options.chunkSize=5x1024x1024] 分片大小(默认5MB)
   * @param {number} [options.concurrency=3] 并发数
   * @param {Object} options.api 接口配置
   * @param {Function} [options.onProgress] 进度回调
   */
  constructor(options) {
    // 显式绑定所有方法
    this.start = this.start.bind(this);
    this.pause = this.pause.bind(this);
    this.resume = this.resume.bind(this);
    this.cancel = this.cancel.bind(this);

    this.file = options.file;
    this.chunkSize = options.chunkSize || 5 * 1024 * 1024;
    this.concurrency = options.concurrency || 3;
    this.api = options.api;
    this.onProgress = options.onProgress;

    // 提前计算分片信息（此时尚未获取服务端状态）
    this.chunks = this.#generateBaseChunks();
    this.uploadId = options?.uploadId || null;
    this.abortControllers = new Map();
    this.queue = null;
    this.isPaused = false;
    this.totalChunks = this.chunks.length; // 预计算分片总数

  }

  /**
   * 开始上传（完整流程）
   */
  async start() {
    try {
      // 1. 初始化上传会话（携带预计算的分片信息）
      const initResponse = await this.api.start({
        name: this.file.name,
        size: this.file.size,
        type: this.file.type,
        chunkSize: this.chunkSize,
        totalChunks: this.totalChunks // 使用预计算的分片总数
      });
      console.log('chunkedUploader -》 start =〉initResponse1', initResponse)
      if(!initResponse) return;
      this.uploadId = initResponse.uploadId;

      // 2. 合并服务端返回的已上传信息
      this.#mergeUploadedChunks(initResponse?.uploadedChunks || []);
      console.log('chunkedUploader -》 start =〉initResponse2', initResponse)
      // 3. 初始化并发队列
      this.queue = new ConcurrentQueue({
        concurrency: this.concurrency,
        retries: 3,
        onProgress: this.#handleProgress.bind(this),
        onComplete: () => this.#finishUpload(),
        onError: (err) => this.#handleError(err)
      });
      console.log('chunkedUploader -》 start =〉initResponse3', initResponse)
      // 4. 添加未完成分片任务
      this.chunks.filter(c => !c.uploaded).forEach(chunk => {
        this.queue.add(() => this.#uploadChunk(chunk), chunk.index);
      });
      console.log('chunkedUploader -》 start =〉initResponse4', initResponse)
    } catch (err) {
      this.#handleError(err);
    }
  }

  /**
   * 暂停上传 (立即中止所有请求)
   */
  async pause() {
    if (!this.isPaused) {
      this.isPaused = true;
      this.queue.pause();

      // 中止所有进行中的请求
      this.abortControllers.forEach(ctrl => ctrl.abort());
      this.abortControllers.clear();

      // 调用服务端暂停接口
      await this.api.pause({ uploadId: this.uploadId });
    }
  }

  /**
   * 恢复上传
   */
  async resume() {
    if (this.isPaused) {
      this.isPaused = false;

      // 获取最新的已上传分片信息
      const status = await this.api.resume({ uploadId: this.uploadId });
      this.chunks.forEach(c => c.uploaded = status.uploadedChunks.includes(c.index));

      this.queue.resume();
    }
  }

  /**
   * 取消上传 (不可恢复)
   */
  async cancel() {
    // 立即中止所有请求
    this.abortControllers.forEach(ctrl => ctrl.abort());
    this.abortControllers.clear();

    // 清空队列
    this.queue?.pause();
    this.queue = null;

    // 调用服务端取消接口
    await this.api.cancel({ uploadId: this.uploadId });
  }

  /****************** 私有方法 ******************/

  // 初始生成基础分片（无服务端状态）
  #generateBaseChunks() {
    const chunks = [];
    let offset = 0;
    let index = 0;

    while (offset < this.file.size) {
      const end = Math.min(offset + this.chunkSize, this.file.size);
      chunks.push({
        index: index++,
        start: offset,
        end,
        blob: this.file.slice(offset, end),
        uploaded: false // 初始状态均为未上传
      });
      offset = end;
    }
    return chunks;
  }

  // 合并服务端返回的已上传分片
  #mergeUploadedChunks(uploadedIndexes) {
    const uploadedSet = new Set(uploadedIndexes);
    this.chunks.forEach(chunk => {
      chunk.uploaded = uploadedSet.has(chunk.index);
    });
  }

  // 分片上传核心方法
  async #uploadChunk(chunk) {
    if (this.isPaused) return;

    const controller = new AbortController();
    this.abortControllers.set(chunk.index, controller);

    try {
      await this.api.uploadChunk({
        uploadId: this.uploadId,
        chunkIndex: chunk.index,
        totalChunks: this.totalChunks, // 携带总分片数
        chunkData: chunk.blob,
        signal: controller.signal,
        headers: {
          'Content-Range': `bytes ${chunk.start}-${chunk.end - 1}/${this.file.size}`,
          'X-Total-Chunks': this.totalChunks
        }
      });

      chunk.uploaded = true;
      this.abortControllers.delete(chunk.index);
    } catch (err) {
      if (err.name !== 'AbortError') throw err;
    }
  }

  async #finishUpload() {
    try {
      const result = await this.api.complete({
        uploadId: this.uploadId,
        chunks: this.chunks.map(c => c.index)
      });
      // 触发完成回调...
    } catch (err) {
      this.#handleError(err);
    }
  }

  #handleProgress(progress) {
    const uploaded = this.chunks.filter(c => c.uploaded).length;
    this.onProgress?.({
      total: this.chunks.length,
      uploaded,
      percentage: (uploaded / this.chunks.length * 100).toFixed(1)
    });
  }

  #handleError(error) {
    // 统一错误处理逻辑...
    console.log('chunkedUploader -》 #handleError', error)
  }
}
// --

