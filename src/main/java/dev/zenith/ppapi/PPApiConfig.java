package dev.zenith.ppapi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class PPApiConfig {
    public boolean enabled = true;
    public int port = 8080;
    /** Master (admin) token: may load/view pearls for ANY player. Keep it private. */
    public String authToken = UUID.randomUUID().toString();
    /** When false, the master token is rejected and only per-user tokens work. */
    public boolean masterTokenEnabled = true;
    public boolean rateLimiter = true;
    public int rateLimitRequestsPerMinute = 30;
    /** Failed logins allowed per minute across ALL callers before bad tokens get 429. Stops token guessing. 0 = off. */
    public int authFailuresPerMinute = 60;
    public boolean commandsAccountOwnerPerms = false;
    /** Per-user tokens, keyed by lowercase Minecraft name. Only a SHA-256 hash of each token is stored. */
    public Map<String, ApiUser> users = new LinkedHashMap<>();

    public static final class ApiUser {
        /** Minecraft username this token is bound to. */
        public String playerName;
        /** Hex SHA-256 of the token. The token itself is never stored. */
        public String tokenHash;
        /** Epoch millis when the token was issued. */
        public long createdAt;
    }
}
