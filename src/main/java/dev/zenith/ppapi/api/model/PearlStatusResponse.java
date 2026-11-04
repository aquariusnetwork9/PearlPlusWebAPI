package dev.zenith.ppapi.api.model;

import java.util.List;

/**
 * loadCommand: the word players whisper to load (PearlPlus autoLoad.loadCommand), so the client mod can intercept it.
 * defaultPearlId: the pearl a bare "load" would use for this player, or null if they must pick one.
 */
public record PearlStatusResponse(
    List<String> pearls,
    List<String> output,
    String minecraftServer,
    String botUsername,
    boolean botConnected,
    String loadCommand,
    String defaultPearlId
) { }
