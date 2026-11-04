package dev.zenith.ppapi.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.zenith.Globals;
import com.zenith.Proxy;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandOutputHelper;
import dev.zenith.ppapi.api.model.ApiErrorResponse;
import dev.zenith.ppapi.api.model.PearlLoadRequest;
import dev.zenith.ppapi.api.model.PearlLoadResponse;
import dev.zenith.ppapi.api.model.PearlStatusRequest;
import dev.zenith.ppapi.api.model.PearlStatusResponse;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.json.JavalinJackson;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static dev.zenith.ppapi.PPApiPlugin.LOG;
import static dev.zenith.ppapi.PPApiPlugin.PLUGIN_CONFIG;

public class PPApiServer {
    private static final String REQUIRED_PLUGIN_ID = "pearlplus";
    private Javalin server;
    private final Cache<String, Integer> rateLimitCache = CacheBuilder.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build();

    public synchronized void start() {
        if (server != null) {
            stop();
        }
        if (!isRequiredPluginAvailable()) {
            LOG.warn("PP API not started. Required plugin '{}' is not loaded.", REQUIRED_PLUGIN_ID);
            return;
        }
        server = createServer();
        server.start(PLUGIN_CONFIG.port);
        LOG.info("PP API started on port {}", PLUGIN_CONFIG.port);
        LOG.info("Master token {}; {} per-user token(s) configured", PLUGIN_CONFIG.masterTokenEnabled ? "enabled" : "disabled", PLUGIN_CONFIG.users.size());
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
            LOG.info("PP API stopped");
        }
    }

    public synchronized boolean isRunning() {
        return server != null && server.jettyServer().started();
    }

    private Javalin createServer() {
        return Javalin.create(config -> {
                var threadPool = new ExecutorThreadPool();
                threadPool.setDaemon(true);
                threadPool.setName("ZenithProxy-PPApi-%d");
                config.jetty.threadPool = threadPool;
                config.http.defaultContentType = "application/json";
                var objectMapper = JavalinJackson.defaultMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                config.jsonMapper(new JavalinJackson(objectMapper, false));
            })
            .beforeMatched(ctx -> {
                if (!isRequiredPluginAvailable()) {
                    ctx.status(503);
                    ctx.json(new ApiErrorResponse("Required plugin is not loaded"));
                    ctx.skipRemainingHandlers();
                    LOG.warn("Denied request from {}: required plugin '{}' not loaded", clientIp(ctx), REQUIRED_PLUGIN_ID);
                    return;
                }
                // Self-service token endpoints need no token (they are how a player gets one).
                // Mojang verifies identity; here we only cap how often one IP may try.
                if (ctx.path().startsWith("/pearlplus/auth/")) {
                    if (!PLUGIN_CONFIG.selfServiceEnabled) {
                        ctx.status(403);
                        ctx.json(new ApiErrorResponse("Self-service tokens are turned off on this bot. Ask its owner for a token"));
                        ctx.skipRemainingHandlers();
                        return;
                    }
                    if (isLimited("auth-ip:" + clientIp(ctx), 10)) {
                        ctx.status(429);
                        ctx.json(new ApiErrorResponse("Too many token requests, try again in a minute"));
                        ctx.skipRemainingHandlers();
                    }
                    return;
                }
                var authHeaderValue = ctx.header("Authorization");
                var identity = UserTokens.authenticate(authHeaderValue);
                // Rate limit per authenticated identity; unauthenticated callers share a per-IP bucket.
                var rateKey = identity != null ? identity.rateLimitKey() : "ip:" + clientIp(ctx);
                if (isRateLimited(rateKey)) {
                    ctx.status(429);
                    ctx.json(new ApiErrorResponse("Rate limit exceeded"));
                    ctx.skipRemainingHandlers();
                    LOG.warn("Rate limit exceeded for {} ({})", rateKey, clientIp(ctx));
                    return;
                }
                if (identity != null) {
                    ctx.attribute(IDENTITY_ATTR, identity);
                    return;
                }
                // Global cap on failed logins (all IPs together). Tokens are short, so this is what
                // makes guessing them impractical even from many IPs. Valid tokens are never affected.
                if (isAuthFailureLimited()) {
                    ctx.status(429);
                    ctx.json(new ApiErrorResponse("Too many failed logins, try again in a minute"));
                    ctx.skipRemainingHandlers();
                    LOG.warn("Failed-login limit reached; rejected request from {}", clientIp(ctx));
                    return;
                }
                String reason = authHeaderValue == null
                    ? "Authorization header missing"
                    : "Invalid auth token";
                ctx.json(new ApiErrorResponse(reason));
                ctx.status(401);
                ctx.skipRemainingHandlers();
                LOG.warn("Denied request from {}: {}", clientIp(ctx), reason);
            })
            .post("/pearlplus/status", ctx -> {
                var req = ctx.bodyAsClass(PearlStatusRequest.class);
                var playerName = req.playerName() == null ? null : req.playerName().trim();
                if (playerName == null || !UserTokens.MC_NAME.matcher(playerName).matches()) {
                    ctx.status(400);
                    ctx.json(new ApiErrorResponse("playerName is required and must be a valid Minecraft name"));
                    return;
                }
                if (!checkAccess(ctx, playerName)) return;
                var snap = readPearlPlus(playerName);
                if (snap.error() != null) {
                    ctx.status(500);
                    ctx.json(new ApiErrorResponse(snap.error()));
                    return;
                }
                var botInfo = readZenithBotInfo();
                ctx.json(new PearlStatusResponse(
                    snap.pearls(),
                    List.of("Loaded pearls from PearlPlus config"),
                    botInfo.minecraftServer(),
                    botInfo.botUsername(),
                    botInfo.botConnected(),
                    snap.loadCommand(),
                    snap.resolveDefault()
                ));
                ctx.status(200);
            })
            .post("/pearlplus/load", ctx -> {
                var req = ctx.bodyAsClass(PearlLoadRequest.class);
                var playerName = req.playerName() == null ? null : req.playerName().trim();
                var pearlId = req.pearlId() == null ? null : req.pearlId().trim();
                if (pearlId != null && pearlId.isEmpty()) pearlId = null;
                if (playerName == null || !UserTokens.MC_NAME.matcher(playerName).matches()
                    || (pearlId != null && !PEARL_ID.matcher(pearlId).matches())) {
                    ctx.status(400);
                    ctx.json(new ApiErrorResponse("playerName is required; pearlId must have no spaces or quotes"));
                    return;
                }
                if (!checkAccess(ctx, playerName)) return;
                var snap = readPearlPlus(playerName);
                if (snap.error() != null) {
                    ctx.status(500);
                    ctx.json(new ApiErrorResponse(snap.error()));
                    return;
                }
                if (!Proxy.getInstance().isConnected()) {
                    ctx.status(503);
                    ctx.json(new ApiErrorResponse("Bot is not connected to the server (offline or in queue)"));
                    return;
                }
                if (snap.pearls().isEmpty()) {
                    ctx.status(404);
                    ctx.json(new ApiErrorResponse("No pearls registered for " + playerName));
                    return;
                }
                if (pearlId == null) {
                    // Bare "load": same rule as whispering "load" with no id.
                    pearlId = snap.resolveDefault();
                    if (pearlId == null) {
                        ctx.status(400);
                        ctx.json(new ApiErrorResponse("You have several pearls, pick one: " + String.join(", ", snap.pearls())));
                        return;
                    }
                } else {
                    String wanted = pearlId;
                    var match = snap.pearls().stream().filter(x -> x.equalsIgnoreCase(wanted)).findFirst();
                    if (match.isEmpty()) {
                        ctx.status(404);
                        ctx.json(new ApiErrorResponse("No pearl named " + wanted + ". Your pearls: " + String.join(", ", snap.pearls())));
                        return;
                    }
                    pearlId = match.get();
                }
                var command = "pp load " + playerName + " " + pearlId;
                var context = executeCommand(command, ctx);
                ctx.json(new PearlLoadResponse("queued", pearlId, context.getMultiLineOutput()));
                ctx.status(200);
            })
            .post("/pearlplus/auth/challenge", ctx -> {
                var req = ctx.bodyAsClass(AuthChallengeRequest.class);
                var playerName = req.playerName() == null ? null : req.playerName().trim();
                if (playerName == null || !UserTokens.MC_NAME.matcher(playerName).matches()) {
                    ctx.status(400);
                    ctx.json(new ApiErrorResponse("playerName is required and must be a valid Minecraft name"));
                    return;
                }
                ctx.json(new AuthChallengeResponse(SelfService.newChallenge(playerName)));
                ctx.status(200);
            })
            .post("/pearlplus/auth/verify", ctx -> {
                var req = ctx.bodyAsClass(AuthVerifyRequest.class);
                var playerName = req.playerName() == null ? null : req.playerName().trim();
                if (playerName == null || !UserTokens.MC_NAME.matcher(playerName).matches()
                    || req.serverId() == null || req.serverId().isBlank()) {
                    ctx.status(400);
                    ctx.json(new ApiErrorResponse("playerName and serverId are required"));
                    return;
                }
                var result = SelfService.verifyAndIssue(playerName, req.serverId().trim(),
                    name -> {
                        var snap = readPearlPlus(name);
                        return snap.error() == null && !snap.pearls().isEmpty();
                    });
                if (result.error() != null) {
                    ctx.status(result.status());
                    ctx.json(new ApiErrorResponse(result.error()));
                    LOG.warn("Self-service token refused for {} from {}: {}", playerName, clientIp(ctx), result.error());
                    return;
                }
                ctx.json(new AuthTokenResponse(result.playerName(), result.token()));
                ctx.status(200);
            });
    }

    public record AuthChallengeRequest(String playerName) { }
    public record AuthChallengeResponse(String serverId) { }
    public record AuthVerifyRequest(String playerName, String serverId) { }
    public record AuthTokenResponse(String playerName, String token) { }

    /** Simple per-key counter in the shared 1-minute cache. */
    private boolean isLimited(String key, int perMinute) {
        synchronized (this) {
            Integer count = rateLimitCache.getIfPresent(key);
            int n = count == null ? 0 : count;
            rateLimitCache.put(key, n + 1);
            return n >= perMinute;
        }
    }

    private static final String IDENTITY_ATTR = "ppapi.identity";
    private static final Pattern PEARL_ID = Pattern.compile("^[^\\s\"'\\\\]{1,64}$");

    /** Per-user tokens may only touch their own player's pearls; the master token may touch any. */
    private boolean checkAccess(Context ctx, String playerName) {
        UserTokens.Identity identity = ctx.attribute(IDENTITY_ATTR);
        if (identity != null && identity.mayAccess(playerName)) return true;
        ctx.status(403);
        ctx.json(new ApiErrorResponse("This token can only access pearls for "
            + (identity == null ? "nobody" : identity.playerName())));
        LOG.warn("Denied request from {}: token for {} tried to access {}", clientIp(ctx),
            identity == null ? "?" : identity.playerName(), playerName);
        return false;
    }

    /** Counts this failed login; true once the global per-minute failure budget is used up. */
    private boolean isAuthFailureLimited() {
        int limit = PLUGIN_CONFIG.authFailuresPerMinute;
        if (limit <= 0) return false;
        synchronized (this) {
            Integer count = rateLimitCache.getIfPresent("authfail");
            int n = count == null ? 0 : count;
            rateLimitCache.put("authfail", n + 1);
            return n >= limit;
        }
    }

    private boolean isRateLimited(String key) {
        if (!PLUGIN_CONFIG.rateLimiter) return false;
        synchronized (this) {
            int reqCount;
            try {
                reqCount = rateLimitCache.get(key, () -> 0);
            } catch (Exception e) {
                reqCount = 0;
            }
            rateLimitCache.put(key, reqCount + 1);
            return reqCount >= PLUGIN_CONFIG.rateLimitRequestsPerMinute;
        }
    }

    /** Real client IP. Behind cloudflared every request comes from loopback, so trust CF-Connecting-IP only then. */
    private static String clientIp(Context ctx) {
        String ip = ctx.ip();
        if (ip != null && (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("[0:0:0:0:0:0:0:1]"))) {
            String cf = ctx.header("CF-Connecting-IP");
            if (cf != null && !cf.isBlank()) return cf.trim();
        }
        return ip;
    }

    private boolean isRequiredPluginAvailable() {
        return Globals.PLUGIN_MANAGER.getPlugin(REQUIRED_PLUGIN_ID) != null;
    }

    private CommandContext executeCommand(String command, Context http) {
        var context = CommandContext.create(command, PPApiCommandSource.INSTANCE);
        UserTokens.Identity identity = http.attribute(IDENTITY_ATTR);
        LOG.info("PP API executed command: {} (token: {}, ip: {})", command,
            identity == null ? "?" : identity.isMaster() ? "master" : identity.playerName(), clientIp(http));
        Globals.COMMAND.execute(context);
        context.getSource().logEmbed(context, context.getEmbed());
        CommandOutputHelper.logMultiLineOutputToTerminal(context.getMultiLineOutput());
        CommandOutputHelper.logMultiLineOutputToDiscord(context.getMultiLineOutput());
        return context;
    }

    /** What PearlPlus knows about one player, read by reflection (works with duccss PearlPlus and PearlPlus-ng). */
    private record PearlSnapshot(List<String> pearls, String playerDefault, String globalDefault,
                                 String loadCommand, String error) {
        /** Player default, then global default (if the player owns it), then their only pearl. */
        String resolveDefault() {
            for (String candidate : new String[] {playerDefault, globalDefault}) {
                if (candidate == null || candidate.isBlank()) continue;
                for (String p : pearls) if (p.equalsIgnoreCase(candidate)) return p;
            }
            return pearls.size() == 1 ? pearls.get(0) : null;
        }
    }

    /** Reads a field by reflection; pass a Class for static fields. Returns null if it does not exist. */
    private static Object field(Object target, String name) {
        if (target == null) return null;
        Class<?> c = target instanceof Class<?> cls ? cls : target.getClass();
        Object instance = target instanceof Class<?> ? null : target;
        try {
            var f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(instance);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private PearlSnapshot readPearlPlus(String playerName) {
        var plugin = Globals.PLUGIN_MANAGER.getPlugin(REQUIRED_PLUGIN_ID);
        if (plugin == null) {
            return new PearlSnapshot(List.of(), null, null, null, "PearlPlus plugin is not loaded");
        }
        Object config = field(plugin.getClass(), "PLUGIN_CONFIG");
        if (config == null) {
            return new PearlSnapshot(List.of(), null, null, null, "PearlPlus config not available");
        }
        if (!(field(config, "players") instanceof java.util.Map<?, ?> players)) {
            return new PearlSnapshot(List.of(), null, null, null, "PearlPlus config players map missing");
        }
        Object globalDefault = field(config, "defaultPearlId");
        Object loadCommand = field(field(config, "autoLoad"), "loadCommand");
        Set<String> pearls = new LinkedHashSet<>();
        String playerDefault = null;
        for (var entry : players.values()) {
            Object name = field(entry, "playerName");
            if (name == null || !name.toString().equalsIgnoreCase(playerName)) continue;
            Object def = field(entry, "defaultPearlId");
            if (def != null && playerDefault == null) playerDefault = def.toString();
            if (field(entry, "pearls") instanceof java.util.Map<?, ?> pearlMap) {
                for (var e : pearlMap.entrySet()) {
                    Object id = field(e.getValue(), "pearlId");
                    if (id == null) id = e.getKey();
                    if (id != null) pearls.add(id.toString());
                }
            }
        }
        List<String> sorted = new ArrayList<>(pearls);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return new PearlSnapshot(sorted, playerDefault,
            globalDefault == null ? null : globalDefault.toString(),
            loadCommand == null || loadCommand.toString().isBlank() ? "load" : loadCommand.toString(),
            null);
    }

    private BotInfo readZenithBotInfo() {
        String server = null;
        String username = null;
        boolean connected = Proxy.getInstance().isConnected();
        var config = Globals.CONFIG;
        if (config != null) {
            if (config.authentication != null) {
                username = config.authentication.username;
            }
            if (config.client != null && config.client.server != null) {
                var address = config.client.server.address;
                if (address != null && !address.isBlank()) {
                    server = address;
                }
            }
        }
        return new BotInfo(server, username, connected);
    }

    private record BotInfo(
        String minecraftServer,
        String botUsername,
        boolean botConnected
    ) { }
}
