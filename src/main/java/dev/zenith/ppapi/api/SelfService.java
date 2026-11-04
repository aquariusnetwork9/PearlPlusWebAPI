package dev.zenith.ppapi.api;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.JsonParser;
import com.zenith.command.api.CommandOutputHelper;
import com.zenith.discord.Embed;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static dev.zenith.ppapi.PPApiPlugin.LOG;
import static dev.zenith.ppapi.PPApiPlugin.PLUGIN_CONFIG;

/**
 * Self-service tokens. The client mod proves the player owns their Minecraft account the same way
 * a server does on join: the mod calls Mojang's session server "join" with a one-time serverId we hand
 * out, then we ask Mojang "hasJoined" for that name + serverId. The player's access token never reaches us.
 */
public final class SelfService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String HAS_JOINED = "https://sessionserver.mojang.com/session/minecraft/hasJoined";
    /** serverId -> lowercase player name. One use, 2 minute lifetime. */
    private static final Cache<String, String> CHALLENGES = CacheBuilder.newBuilder()
        .expireAfterWrite(2, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private SelfService() { }

    public record Result(int status, String error, String playerName, String token) {
        static Result error(int status, String error) { return new Result(status, error, null, null); }
    }

    public static String newChallenge(String playerName) {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        String serverId = HexFormat.of().formatHex(bytes);
        CHALLENGES.put(serverId, playerName.toLowerCase(Locale.ROOT));
        return serverId;
    }

    /**
     * Checks the challenge with Mojang, applies the eligibility rule and cooldown, then issues a token.
     * @param hasPearls returns true if the (Mojang-confirmed) name has at least one pearl on this bot
     */
    public static Result verifyAndIssue(String playerName, String serverId, java.util.function.Predicate<String> hasPearls) {
        String expected = serverId == null ? null : CHALLENGES.getIfPresent(serverId);
        if (expected == null || !expected.equals(playerName.toLowerCase(Locale.ROOT))) {
            return Result.error(400, "Challenge expired or invalid, try again");
        }
        CHALLENGES.invalidate(serverId); // one use only

        String confirmedName;
        try {
            var url = HAS_JOINED + "?username=" + URLEncoder.encode(playerName, StandardCharsets.UTF_8)
                + "&serverId=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8);
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "PearlPlusWebAPI").GET().build();
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500) { // Mojang hiccup: retry once
                Thread.sleep(1000);
                response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            }
            if (response.statusCode() >= 500) {
                LOG.warn("Self-service token: Mojang session server error HTTP {} for {}", response.statusCode(), playerName);
                return Result.error(502, "Mojang's session server had an error, try again in a moment");
            }
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                LOG.warn("Self-service token denied for {}: Mojang did not confirm the session (HTTP {})", playerName, response.statusCode());
                return Result.error(403, "Mojang could not confirm you own " + playerName + ". Restart your game launcher and try again");
            }
            confirmedName = JsonParser.parseString(response.body()).getAsJsonObject().get("name").getAsString();
        } catch (Exception e) {
            LOG.error("Self-service token: Mojang session check failed", e);
            return Result.error(502, "Could not reach Mojang's session server, try again later");
        }

        if (!hasPearls.test(confirmedName)) {
            return Result.error(403, "No pearls are registered for " + confirmedName + " on this bot yet");
        }

        long cooldownMs = TimeUnit.MINUTES.toMillis(Math.max(0, PLUGIN_CONFIG.selfServiceCooldownMinutes));
        long issuedAt = UserTokens.issuedAt(confirmedName);
        long waitMs = issuedAt + cooldownMs - System.currentTimeMillis();
        if (issuedAt > 0 && waitMs > 0) {
            long minutes = Math.max(1, TimeUnit.MILLISECONDS.toMinutes(waitMs + 59_999));
            return Result.error(429, "You got a token recently. You can get a new one in " + minutes + " min");
        }

        boolean replaced = issuedAt > 0;
        String token = UserTokens.issue(confirmedName);
        LOG.info("PP API self-service token for {} (only works for {}'s pearls): {}", confirmedName, confirmedName, token);
        try {
            var embed = Embed.builder()
                .title(replaced ? "PP API Token Replaced (self-service)" : "PP API Token Created (self-service)")
                .description("**" + confirmedName + "** verified their Minecraft account through the mod."
                    + (replaced ? " Their old token no longer works." : ""))
                .addField("Token", "`" + token + "`")
                .primaryColor();
            CommandOutputHelper.logEmbedOutputToTerminal(embed);
            CommandOutputHelper.logEmbedOutputToDiscord(embed);
        } catch (Exception e) {
            LOG.warn("Could not post self-service token embed", e);
        }
        return new Result(200, null, confirmedName, token);
    }
}
