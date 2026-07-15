package cn.flying.verifier.internal;

import java.util.LinkedHashMap;
import java.util.Map;

/** Exact bounded bytes of the eight signed proof archive entries. */
public record ParsedProofArchive(Map<String, byte[]> entries) {

    /** Defensively copies every parsed entry. */
    public ParsedProofArchive {
        LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>();
        if (entries != null) {
            entries.forEach((name, bytes) -> copy.put(name, bytes == null ? new byte[0] : bytes.clone()));
        }
        entries = Map.copyOf(copy);
    }

    /** Returns an immutable deep copy without exposing the stored entry arrays. */
    @Override
    public Map<String, byte[]> entries() {
        LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>();
        entries.forEach((name, bytes) -> copy.put(name, bytes.clone()));
        return Map.copyOf(copy);
    }

    /** Returns a defensive copy of one required entry. */
    public byte[] required(String name) {
        byte[] value = entries.get(name);
        if (value == null) {
            throw new ProofFormatException(
                    cn.flying.verifier.model.VerificationCode.ARCHIVE_ENTRY_INVALID,
                    "Required proof archive entry is missing: " + name);
        }
        return value.clone();
    }
}
