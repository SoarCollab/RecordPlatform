// concurrentQueue.js

/**
 * 并发队列类，用于管理异步任务的并发执行，支持优先级、重试、暂停/恢复、取消等功能
 */
export class ConcurrentQueue {
  /**
   * 构造函数，初始化并发队列配置及状态
   * @param {Object} options 配置选项
   * @param {number} [options.concurrency=3] 最大并发数
   * @param {boolean} [options.autoStart=true] 是否自动启动队列
   * @param {number} [options.retries=3] 任务失败重试次数
   * @param {number} [options.retryDelay=1000] 重试延迟时间(毫秒)
   * @param {Function} [options.onProgress] 进度变化回调
   * @param {Function} [options.onComplete] 全部任务完成回调
   * @param {Function} [options.onError] 任务失败回调
   * @param {Function} [options.onPause] 暂停回调
   * @param {Function} [options.onResume] 恢复回调
   */
  constructor(options = {}) {
    // 合并默认配置和用户配置
    const defaults = {
      concurrency: 3,// 最大并发数
      autoStart: true,// 是否自动启动队列
      retries: 3,// 任务失败重试次数
      retryDelay: 1000,// 重试延迟时间
      onProgress: null,// 进度变化回调
      onComplete: null,// 全部任务完成回调
      onError: null,// 任务失败回调
      onPause: null,// 暂停回调
      onResume: null// 恢复回调
    };

    this.config = { ...defaults, ...options };
    this.queue = [];          // 等待队列，存储待处理任务
    this.active = new Map();  // 活跃任务映射表，存储正在执行的任务 { id: job }
    this.paused = false;      // 队列是否暂停
    this.taskId = 0;          // 任务ID计数器
    this.stats = {           // 统计信息
      total: 0,       // 总任务数
      processed: 0,   // 已处理数（包括成功和失败）
      succeeded: 0,   // 成功数
      failed: 0,      // 失败数
      retries: 0      // 总重试次数
    };
  }

  /**
   * 添加任务到队列
   * @param {Function} task 异步任务函数，接收{ signal, attempt }参数
   * @param {number} [priority=0] 任务优先级，数值越大优先级越高
   * @returns {number} 返回任务ID
   */
  add(task, priority = 0) {
    const id = ++this.taskId;
    const job = {
      id,
      task,
      priority,
      retryCount: 0,        // 当前重试次数
      status: 'pending',    // 任务状态
      abortController: null // 中断控制器
    };

    // 按优先级插入队列（降序排列）
    const index = this.queue.findIndex(j => j.priority < priority);
    if (index === -1) {
      this.queue.push(job);
    } else {
      this.queue.splice(index, 0, job);
    }

    this.stats.total++;

    // 自动启动且未暂停时触发任务执行
    if (this.config.autoStart && !this.paused) {
      this.#next();
    }

    return id;
  }

  /**
   * 暂停队列执行
   * - 中止所有进行中的任务
   * - 清空活跃任务映射
   */
  pause() {
    if (this.paused) return;

    this.paused = true;
    // 中止所有活跃任务
    this.active.forEach(job => {
      job.abortController?.abort();
      job.status = 'paused';
    });
    this.active.clear();

    this.config.onPause?.();
  }

  /**
   * 恢复队列执行
   * - 将暂停的活跃任务重新加入队列
   * - 触发批量任务执行
   */
  resume() {
    if (!this.paused) return;

    this.paused = false;
    // 将未完成的任务重新加入队列前端
    this.queue.unshift(...Array.from(this.active.values()));
    this.active.clear();
    this.#nextBatch(); // 启动并发任务

    this.config.onResume?.();
  }

  /**
   * 取消指定任务
   * @param {number} id 任务ID
   * @returns {boolean} 是否取消成功
   */
  cancel(id) {
    // 从等待队列中移除
    const index = this.queue.findIndex(j => j.id === id);
    if (index !== -1) {
      this.queue.splice(index, 1);
      this.stats.total--;
      return true;
    }

    // 中止正在执行的任务
    const job = this.active.get(id);
    if (job) {
      job.abortController?.abort();
      this.active.delete(id);
      this.stats.processed++;
      this.stats.failed++;
      return true;
    }

    return false;
  }

  /**
   * 私有方法：执行下一个任务
   * - 控制并发数量
   * - 递归调用自身填充并发槽
   */
  async #next() {
    // 暂停状态或达到并发限制时停止
    if (this.paused || this.active.size >= this.config.concurrency) return;

    // 取出最高优先级任务
    const job = this.queue.shift();
    if (!job) {
      // 队列为空且无活跃任务时触发完成回调
      if (this.active.size === 0) this.config.onComplete?.();
      return;
    }

    job.status = 'active';
    job.abortController = new AbortController(); // 创建中断控制器

    // 添加到活跃任务映射
    this.active.set(job.id, job);

    // 执行任务并处理完成逻辑
    this.#executeJob(job).finally(() => {
      this.active.delete(job.id);
      this.stats.processed++;  // 更新已处理计数
      this.#updateProgress();  // 触发进度更新
      this.#next();            // 继续执行后续任务
    });

    // 递归调用以填满并发槽
    this.#next();
  }

  /**
   * 私有方法：批量启动任务直到达到并发限制
   */
  #nextBatch() {
    Array.from({ length: this.config.concurrency }).forEach(() => this.#next());
  }

  /**
   * 私有方法：执行具体任务（包含重试逻辑）
   * @param {Object} job 任务对象
   */
  async #executeJob(job) {
    try {
      await this.#runWithRetries(job);
      job.status = 'success';
      this.stats.succeeded++;
    } catch (error) {
      // 处理中止错误与其他错误
      job.status = error.name === 'AbortError' ? 'aborted' : 'failed';
      this.stats.failed++;
      this.config.onError?.(error, job);
      if (error.name !== 'AbortError') throw error;
    }
  }

  /**
   * 私有方法：带重试机制的任务执行
   * @param {Object} job 任务对象
   */
  async #runWithRetries(job) {
    let attempt = 0;

    while (attempt <= this.config.retries) {
      try {
        // 执行任务并传入执行上下文
        return await job.task({
          signal: job.abortController.signal, // 中断信号
          attempt: attempt + 1                // 当前尝试次数
        });
      } catch (error) {
        // 中止错误直接抛出
        if (error.name === 'AbortError') throw error;

        attempt++;
        this.stats.retries++;

        // 达到重试上限时抛出错误
        if (attempt > this.config.retries) {
          throw error;
        }

        // 更新状态并延迟重试
        job.status = `retrying (${attempt}/${this.config.retries})`;
        await new Promise(r => setTimeout(r, this.config.retryDelay));
      }
    }
  }

  /**
   * 私有方法：更新进度并触发回调
   */
  #updateProgress() {
    const progress = {
      total: this.stats.total,
      processed: this.stats.processed,
      succeeded: this.stats.succeeded,
      failed: this.stats.failed,
      percentage: Math.round((this.stats.processed / this.stats.total) * 100) || 0
    };
    this.config.onProgress?.(progress);
  }

  /**
   * 获取当前队列状态
   * @returns {Object} 包含统计信息和队列状态的对象
   */
  getStatus() {
    return {
      ...this.stats,
      active: this.active.size,  // 活跃任务数
      queued: this.queue.length, // 等待任务数
      isPaused: this.paused      // 是否暂停
    };
  }
}
// ---
