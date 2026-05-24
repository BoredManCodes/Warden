package io.warden.api;

import java.util.List;

/**
 * Row in the api_keys table. The token's plaintext is never present here -
 * only the hash, plus a short prefix kept around so admins can tell their
 * keys apart on the dashboard.
 */
public record ApiKey(
        long id,
        String label,
        String prefix,
        String tokenHash,
        List<String> scopes,
        String createdBy,
        long createdAt,
        Long lastUsedAt,
        Long revokedAt
) {
    public boolean isRevoked() { return revokedAt != null; }

    public boolean hasScope(ApiScope scope) {
        return scope != null && scopes != null && scopes.contains(scope.key());
    }
}
