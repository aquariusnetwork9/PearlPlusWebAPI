package dev.zenith.ppapi.api.model;

/** pearlId is optional: when missing, the player's default pearl is used (same as whispering a bare "load"). */
public record PearlLoadRequest(
    String playerName,
    String pearlId
) { }
