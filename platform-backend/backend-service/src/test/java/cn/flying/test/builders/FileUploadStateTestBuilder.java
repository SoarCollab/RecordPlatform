package cn.flying.test.builders;

import cn.flying.dao.vo.file.FileUploadState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Test data builder for FileUploadState.
 * Provides fluent API for creating test fixtures for upload sessions.
 */
public class FileUploadStateTestBuilder {

    private static int clientIdCounter = 1;

    public static FileUploadState anUploadState() {
        String clientId = "client_" + (clientIdCounter++);
        FileUploadState state = new FileUploadState(
                100L,           // userId
                "test_file.txt",
                1024 * 1024L,   // 1MB
                "text/plain",
                clientId,
                256 * 1024,     // 256KB chunk size
                4               // 4 chunks
        );
        return state;
    }

    public static FileUploadState anUploadState(Consumer<FileUploadState> customizer) {
        FileUploadState state = anUploadState();
        customizer.accept(state);
        return state;
    }

    public static FileUploadState anUploadStateForUser(Long userId) {
        return anUploadState(s -> {
            // Use reflection or create new instance since userId is set in constructor
            try {
                var field = FileUploadState.class.getDeclaredField("userId");
                field.setAccessible(true);
                field.set(s, userId);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set userId", e);
            }
        });
    }

    public static FileUploadState anUploadStateWithClientId(String clientId) {
        return anUploadState(s -> {
            try {
                var field = FileUploadState.class.getDeclaredField("clientId");
                field.setAccessible(true);
                field.set(s, clientId);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set clientId", e);
            }
        });
    }

    public static FileUploadState anUploadStateWithChunks(int totalChunks, int uploadedCount, int processedCount) {
        FileUploadState state = anUploadState(s -> {
            try {
                var field = FileUploadState.class.getDeclaredField("totalChunks");
                field.setAccessible(true);
                field.set(s, totalChunks);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set totalChunks", e);
            }
        });

        // Add uploaded chunks
        Set<Integer> uploaded = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < uploadedCount; i++) {
            uploaded.add(i);
        }
        state.setUploadedChunks(uploaded);

        // Add processed chunks
        Set<Integer> processed = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < processedCount; i++) {
            processed.add(i);
        }
        state.setProcessedChunks(processed);

        return state;
    }

    public static FileUploadState aCompletedUploadState() {
        return anUploadStateWithChunks(4, 4, 4);
    }

    public static FileUploadState aPartiallyUploadedState() {
        return anUploadStateWithChunks(4, 2, 1);
    }

    public static void resetClientIdCounter() {
        clientIdCounter = 1;
    }
}
