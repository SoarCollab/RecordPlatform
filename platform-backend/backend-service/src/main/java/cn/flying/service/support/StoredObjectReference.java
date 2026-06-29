package cn.flying.service.support;

/**
 * Ordered object-storage reference persisted inside the blockchain file content field.
 */
public record StoredObjectReference(
        int index,
        String cipherHash,
        String storagePath
) {
}
