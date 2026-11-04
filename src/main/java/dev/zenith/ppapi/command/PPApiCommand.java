package dev.zenith.ppapi.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import dev.zenith.ppapi.api.UserTokens;


import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.command.brigadier.CustomStringArgumentType.getString;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static dev.zenith.ppapi.PPApiPlugin.LOG;
import static dev.zenith.ppapi.PPApiPlugin.PLUGIN_CONFIG;
import static dev.zenith.ppapi.PPApiPlugin.SERVER;

public class PPApiCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("ppApi")
            .category(CommandCategory.MODULE)
            .description("""
                Manages the PP API for interacting with this ZenithProxy instance.
                Per-user tokens only work for that player's own pearls. A new token is shown once,
                in this reply (so the admin Discord log keeps it) and in the console log.
                """)
            .usageLines(
                "on/off",
                "port <port>",
                "auth <token>",
                "auth regenerate",
                "masterToken on/off",
                "user add <playerName>",
                "user reset <playerName>",
                "user remove <playerName>",
                "user list",
                "commandsAccountOwnerPerms on/off",
                "rateLimiter on/off",
                "rateLimiter requestsPerMinute <requestCount>"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("ppApi").requires(Command::validateAccountOwner)
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.enabled = getToggle(c, "toggle");
                if (PLUGIN_CONFIG.enabled) {
                    SERVER.start();
                } else {
                    SERVER.stop();
                }
                c.getSource().getEmbed()
                    .title("PP API " + toggleStrCaps(PLUGIN_CONFIG.enabled));
            }))
            .then(literal("port").then(argument("portArg", integer(1, 65535)).executes(c -> {
                PLUGIN_CONFIG.port = getInteger(c, "portArg");
                if (PLUGIN_CONFIG.enabled) {
                    SERVER.start();
                }
                c.getSource().getEmbed()
                    .title("PP API Port Set");
            })))
            .then(literal("auth")
                .then(literal("regenerate").executes(c -> {
                    PLUGIN_CONFIG.authToken = UserTokens.newToken();
                    LOG.info("New PP API MASTER token (any player): {}", PLUGIN_CONFIG.authToken);
                    c.getSource().getEmbed()
                        .title("PP API Master Token Regenerated")
                        .description("The new token was written to the console log only. The old one no longer works.");
                }))
                .then(argument("token", wordWithChars()).executes(c -> {
                    PLUGIN_CONFIG.authToken = getString(c, "token");
                    c.getSource().getEmbed()
                        .title("PP API Auth Token Set");
                })))
            .then(literal("masterToken").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.masterTokenEnabled = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Master Token " + toggleStrCaps(PLUGIN_CONFIG.masterTokenEnabled));
            })))
            .then(literal("user")
                .then(literal("add").then(argument("player", wordWithChars()).executes(c -> { issueToken(c, false); })))
                .then(literal("reset").then(argument("player", wordWithChars()).executes(c -> { issueToken(c, true); })))
                .then(literal("remove").then(argument("player", wordWithChars()).executes(c -> {
                    var player = getString(c, "player");
                    boolean removed = UserTokens.revoke(player);
                    c.getSource().getEmbed()
                        .title(removed ? "PP API Token Revoked" : "No Token Found")
                        .description(removed ? player + "'s token no longer works." : player + " has no PP API token.");
                })))
                .then(literal("list").executes(c -> {
                    var names = UserTokens.listPlayers();
                    c.getSource().getEmbed()
                        .title("PP API Users (" + names.size() + ")")
                        .description(names.isEmpty() ? "None" : String.join(", ", names));
                })))
            .then(literal("commandsAccountOwnerPerms").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.commandsAccountOwnerPerms = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Commands Account Owner Perms " + toggleStrCaps(PLUGIN_CONFIG.commandsAccountOwnerPerms));
            })))
            .then(literal("rateLimiter")
                .then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.rateLimiter = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                        .title("Rate Limiter " + toggleStrCaps(PLUGIN_CONFIG.rateLimiter));
                }))
                .then(literal("requestsPerMinute").then(argument("requests", integer(1)).executes(c -> {
                    PLUGIN_CONFIG.rateLimitRequestsPerMinute = getInteger(c, "requests");
                    c.getSource().getEmbed()
                        .title("Rate Limiter Requests Per Minute Set");
                }))));
    }

    private void issueToken(com.mojang.brigadier.context.CommandContext<CommandContext> c, boolean mustExist) {
        var player = getString(c, "player");
        if (!UserTokens.MC_NAME.matcher(player).matches()) {
            c.getSource().getEmbed().title("Invalid Player Name").description("Use the exact Minecraft username.").errorColor();
            return;
        }
        boolean existed = UserTokens.listPlayers().stream().anyMatch(n -> n.equalsIgnoreCase(player));
        if (mustExist && !existed) {
            c.getSource().getEmbed().title("No Token Found").description(player + " has no PP API token. Use `ppApi user add " + player + "`.").errorColor();
            return;
        }
        String token = UserTokens.issue(player);
        LOG.info("PP API token for {} (only works for {}'s pearls): {}", player, player, token);
        c.getSource().getEmbed()
            .title(existed ? "PP API Token Replaced" : "PP API Token Created")
            .description("Token for **" + player + "** (only works for their own pearls)."
                + (existed ? " Their old token no longer works." : ""))
            // Shown in the reply so the bot's Discord log (admin-only channel) keeps a record of it.
            .addField("Token", "`" + token + "`");
    }

    @Override
    public void defaultEmbed(Embed embed) {
        embed
            .addField("PP API", SERVER.isRunning() ? "Running" : "Stopped")
            .addField("Port", PLUGIN_CONFIG.port)
            .addField("Master Token", PLUGIN_CONFIG.masterTokenEnabled ? "Enabled (hidden)" : "Disabled")
            .addField("Per-User Tokens", UserTokens.listPlayers().size())
            .addField("Commands Account Owner Perms", PLUGIN_CONFIG.commandsAccountOwnerPerms)
            .addField("Rate Limiter", toggleStr(PLUGIN_CONFIG.rateLimiter))
            .addField("Rate Limit Requests Per Minute", PLUGIN_CONFIG.rateLimitRequestsPerMinute)
            .primaryColor();
    }
}
