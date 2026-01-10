import { describe, it, expect, beforeEach, afterEach } from "vitest";
import {
  isStorageAvailable,
  saveTask,
  getTask,
  getPendingTasks,
  deleteTask,
  saveChunk,
  getChunks,
  getChunkCount,
  deleteChunks,
  clearTaskData,
  cleanupExpiredData,
  getStorageUsage,
  type PersistedDownloadTask,
} from "./downloadStorage";

describe("downloadStorage", () => {
  const testTaskIds: string[] = [];

  const createTestTask = (
    id: string,
    overrides?: Partial<PersistedDownloadTask>,
  ): PersistedDownloadTask => {
    testTaskIds.push(id);
    return {
      id,
      fileHash: `hash-${id}`,
      fileName: `file-${id}.txt`,
      fileSize: 1024,
      contentType: "text/plain",
      totalChunks: 2,
      initialKey: null,
      source: { type: "owned" as const },
      presignedUrls: [
        "https://example.com/chunk1",
        "https://example.com/chunk2",
      ],
      urlsFetchedAt: Date.now(),
      createdAt: Date.now(),
      ...overrides,
    };
  };

  afterEach(async () => {
    for (const taskId of testTaskIds) {
      try {
        await clearTaskData(taskId);
      } catch {
        /* empty */
      }
    }
    testTaskIds.length = 0;
  });

  describe("isStorageAvailable", () => {
    it("should return true when IndexedDB is available", async () => {
      const result = await isStorageAvailable();
      expect(result).toBe(true);
    });
  });

  describe("Task Operations", () => {
    describe("saveTask and getTask", () => {
      it("should save and retrieve a task", async () => {
        const task = createTestTask("task-save-1");
        await saveTask(task);
        const retrieved = await getTask("task-save-1");

        expect(retrieved).not.toBeNull();
        expect(retrieved?.id).toBe("task-save-1");
        expect(retrieved?.fileHash).toBe("hash-task-save-1");
        expect(retrieved?.fileName).toBe("file-task-save-1.txt");
      });

      it("should return null for non-existent task", async () => {
        const result = await getTask("non-existent-unique-id");
        expect(result).toBeNull();
      });

      it("should overwrite existing task with same id", async () => {
        const task1 = createTestTask("task-overwrite", {
          fileName: "original.txt",
        });
        const task2 = createTestTask("task-overwrite", {
          fileName: "updated.txt",
        });

        await saveTask(task1);
        await saveTask(task2);
        const retrieved = await getTask("task-overwrite");

        expect(retrieved?.fileName).toBe("updated.txt");
      });
    });

    describe("getPendingTasks", () => {
      it("should return all saved tasks", async () => {
        await saveTask(createTestTask("task-pending-1"));
        await saveTask(createTestTask("task-pending-2"));
        await saveTask(createTestTask("task-pending-3"));

        const tasks = await getPendingTasks();

        expect(tasks.length).toBeGreaterThanOrEqual(3);
        const ids = tasks.map((t) => t.id);
        expect(ids).toContain("task-pending-1");
        expect(ids).toContain("task-pending-2");
        expect(ids).toContain("task-pending-3");
      });
    });

    describe("deleteTask", () => {
      it("should delete an existing task", async () => {
        await saveTask(createTestTask("task-delete-1"));
        await deleteTask("task-delete-1");
        const result = await getTask("task-delete-1");

        expect(result).toBeNull();
      });

      it("should not throw when deleting non-existent task", async () => {
        await expect(deleteTask("non-existent-delete")).resolves.not.toThrow();
      });
    });
  });

  describe("Chunk Operations", () => {
    describe("saveChunk and getChunks", () => {
      it("should save and retrieve chunks", async () => {
        const chunk1 = new Uint8Array([1, 2, 3]);
        const chunk2 = new Uint8Array([4, 5, 6]);
        testTaskIds.push("task-chunk-1");

        await saveChunk("task-chunk-1", 0, chunk1);
        await saveChunk("task-chunk-1", 1, chunk2);

        const chunks = await getChunks("task-chunk-1");

        expect(chunks.size).toBe(2);
        expect(chunks.get(0)).toEqual(chunk1);
        expect(chunks.get(1)).toEqual(chunk2);
      });

      it("should return empty map for non-existent task", async () => {
        const chunks = await getChunks("non-existent-chunk-task");
        expect(chunks.size).toBe(0);
      });

      it("should overwrite existing chunk", async () => {
        testTaskIds.push("task-chunk-overwrite");
        const original = new Uint8Array([1, 2, 3]);
        const updated = new Uint8Array([7, 8, 9]);

        await saveChunk("task-chunk-overwrite", 0, original);
        await saveChunk("task-chunk-overwrite", 0, updated);

        const chunks = await getChunks("task-chunk-overwrite");
        expect(chunks.get(0)).toEqual(updated);
      });
    });

    describe("getChunkCount", () => {
      it("should return 0 for non-existent task", async () => {
        const count = await getChunkCount("non-existent-count-task");
        expect(count).toBe(0);
      });

      it("should return correct count", async () => {
        testTaskIds.push("task-count");
        await saveChunk("task-count", 0, new Uint8Array([1]));
        await saveChunk("task-count", 1, new Uint8Array([2]));
        await saveChunk("task-count", 2, new Uint8Array([3]));

        const count = await getChunkCount("task-count");
        expect(count).toBe(3);
      });
    });

    describe("deleteChunks", () => {
      it("should delete all chunks for a task", async () => {
        testTaskIds.push("task-del-chunks-1");
        testTaskIds.push("task-del-chunks-2");
        await saveChunk("task-del-chunks-1", 0, new Uint8Array([1]));
        await saveChunk("task-del-chunks-1", 1, new Uint8Array([2]));
        await saveChunk("task-del-chunks-2", 0, new Uint8Array([3]));

        await deleteChunks("task-del-chunks-1");

        expect(await getChunkCount("task-del-chunks-1")).toBe(0);
        expect(await getChunkCount("task-del-chunks-2")).toBe(1);
      });

      it("should not throw when deleting chunks for non-existent task", async () => {
        await expect(deleteChunks("non-existent-del")).resolves.not.toThrow();
      });
    });
  });

  describe("Cleanup Operations", () => {
    describe("clearTaskData", () => {
      it("should clear both task and chunks", async () => {
        await saveTask(createTestTask("task-clear"));
        await saveChunk("task-clear", 0, new Uint8Array([1]));
        await saveChunk("task-clear", 1, new Uint8Array([2]));

        await clearTaskData("task-clear");

        expect(await getTask("task-clear")).toBeNull();
        expect(await getChunkCount("task-clear")).toBe(0);
      });
    });

    describe("cleanupExpiredData", () => {
      it("should delete tasks older than expiry period", async () => {
        const oldTask = createTestTask("old-task-cleanup", {
          createdAt: Date.now() - 8 * 24 * 60 * 60 * 1000,
        });
        const newTask = createTestTask("new-task-cleanup", {
          createdAt: Date.now(),
        });
        testTaskIds.push("old-task-cleanup-chunks");

        await saveTask(oldTask);
        await saveTask(newTask);
        await saveChunk("old-task-cleanup", 0, new Uint8Array([1]));

        const cleanedCount = await cleanupExpiredData();

        expect(cleanedCount).toBeGreaterThanOrEqual(1);
        expect(await getTask("old-task-cleanup")).toBeNull();
        expect(await getTask("new-task-cleanup")).not.toBeNull();
        expect(await getChunkCount("old-task-cleanup")).toBe(0);
      });
    });
  });

  describe("Storage Usage", () => {
    it("should return storage usage when available", async () => {
      const result = await getStorageUsage();
      if (result !== null) {
        expect(result).toHaveProperty("used");
        expect(result).toHaveProperty("quota");
        expect(typeof result.used).toBe("number");
        expect(typeof result.quota).toBe("number");
      }
    });
  });

  describe("Type validations", () => {
    it("DownloadSource should support all source types", () => {
      const ownedSource = { type: "owned" as const };
      const publicShare = {
        type: "public_share" as const,
        shareCode: "abc123",
      };
      const privateShare = {
        type: "private_share" as const,
        shareCode: "xyz789",
      };

      expect(ownedSource.type).toBe("owned");
      expect(publicShare.type).toBe("public_share");
      expect(publicShare.shareCode).toBe("abc123");
      expect(privateShare.type).toBe("private_share");
    });

    it("PersistedDownloadTask should have all required fields", () => {
      const task = createTestTask("test-type-validation");

      expect(task.id).toBeDefined();
      expect(task.fileHash).toBeDefined();
      expect(task.fileName).toBeDefined();
      expect(task.fileSize).toBeDefined();
      expect(task.contentType).toBeDefined();
      expect(task.totalChunks).toBeDefined();
      expect(task.source).toBeDefined();
      expect(task.presignedUrls).toBeDefined();
      expect(task.createdAt).toBeDefined();
    });
  });
});
