package io.warden.debug;

public record DebugReport(
        String id,
        long   createdAt,
        String label,
        String encryptedPayload,
        String decryptKey,      // base64url raw AES-256 key (stored for share-link reconstruction)
        String analysisStatus   // "pending" | "done" | "failed" | "skipped"
) {}
