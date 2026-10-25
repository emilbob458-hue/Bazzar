package bazaar.bazzar.client;

final class AltLogic {
    static final long BROADCAST_TTL_MS = 30_000L;

    private AltLogic() {
    }

    static boolean shouldExecute(
            String command,
            long issuedMs,
            long lastSeenMs,
            long nowMs,
            String normalizedIssuer,
            String normalizedLocal,
            boolean localIsPayer) {
        if (command == null || command.isBlank()) {
            return false;
        }
        if (issuedMs <= 0L || issuedMs == lastSeenMs) {
            return false;
        }
        if (nowMs - issuedMs > BROADCAST_TTL_MS) {
            return false;
        }
        if (normalizedLocal == null || normalizedLocal.isEmpty()) {
            return false;
        }
        if (normalizedLocal.equals(normalizedIssuer)) {
            return false;
        }
        return localIsPayer;
    }
}
