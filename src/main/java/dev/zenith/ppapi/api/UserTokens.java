package dev.zenith.ppapi.api;

import dev.zenith.ppapi.PPApiConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static dev.zenith.ppapi.PPApiPlugin.PLUGIN_CONFIG;

/**
 * Per-user API tokens. Each token is bound to one Minecraft username and may only
 * view/load that player's pearls. Only SHA-256 hashes are kept in the config.
 */
public final class UserTokens {
    public static final Pattern MC_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();

    private UserTokens() { }

    /** Who a request is authenticated as. playerName == null means master/admin (any player). */
    public record Identity(String playerName) {
        public boolean isMaster() { return playerName == null; }
        public String rateLimitKey() { return isMaster() ? "master" : "user:" + playerName.toLowerCase(Locale.ROOT); }
        public boolean mayAccess(String requestedPlayer) {
            return isMaster() || playerName.equalsIgnoreCase(requestedPlayer);
        }
    }

    /** Returns the identity for a raw Authorization header value, or null if it is not valid. */
    public static Identity authenticate(String header) {
        if (header == null) return null;
        String token = header.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) token = token.substring(7).trim();
        if (token.isEmpty()) return null;

        String master = PLUGIN_CONFIG.authToken;
        if (PLUGIN_CONFIG.masterTokenEnabled && master != null && !master.isEmpty()
            && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), master.getBytes(StandardCharsets.UTF_8))) {
            return new Identity(null);
        }

        byte[] hash = sha256(token);
        synchronized (LOCK) {
            for (PPApiConfig.ApiUser user : PLUGIN_CONFIG.users.values()) {
                if (user == null || user.tokenHash == null || user.playerName == null) continue;
                byte[] stored;
                try {
                    stored = HexFormat.of().parseHex(user.tokenHash);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (MessageDigest.isEqual(hash, stored)) {
                    return new Identity(user.playerName);
                }
            }
        }
        return null;
    }

    /** Creates (or replaces) the token for a player and returns the plaintext token. Shown once only. */
    public static String issue(String playerName) {
        String token = newToken();
        var user = new PPApiConfig.ApiUser();
        user.playerName = playerName;
        user.tokenHash = HexFormat.of().formatHex(sha256(token));
        user.createdAt = System.currentTimeMillis();
        synchronized (LOCK) {
            PLUGIN_CONFIG.users.put(playerName.toLowerCase(Locale.ROOT), user);
        }
        return token;
    }

    /**
     * Tokens are 7 characters from an alphabet without look-alikes (no 0/O, 1/l/I): 57^7, about 2 trillion.
     * That is short enough to type, so brute force is stopped by the global failed-auth limit in PPApiServer.
     */
    public static final int TOKEN_LENGTH = 7;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    /** A fresh random token that does not collide with the master token or any existing user token. */
    public static String newToken() {
        while (true) {
            StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
            for (int i = 0; i < TOKEN_LENGTH; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            String token = sb.toString();
            if (token.equals(PLUGIN_CONFIG.authToken)) continue;
            String hash = HexFormat.of().formatHex(sha256(token));
            boolean clash;
            synchronized (LOCK) {
                clash = PLUGIN_CONFIG.users.values().stream().anyMatch(u -> u != null && hash.equalsIgnoreCase(u.tokenHash));
            }
            if (!clash) return token;
        }
    }

    /** Removes a player's token. Returns false if they had none. */
    public static boolean revoke(String playerName) {
        synchronized (LOCK) {
            return PLUGIN_CONFIG.users.remove(playerName.toLowerCase(Locale.ROOT)) != null;
        }
    }

    public static List<String> listPlayers() {
        synchronized (LOCK) {
            List<String> names = new ArrayList<>();
            for (PPApiConfig.ApiUser user : PLUGIN_CONFIG.users.values()) {
                if (user != null && user.playerName != null) names.add(user.playerName);
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
