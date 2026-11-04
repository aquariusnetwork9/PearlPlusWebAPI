package dev.zenith.ppapi.api.model;

import java.util.List;

/** pearlId: the pearl actually queued (resolved from the default when the request had none). */
public record PearlLoadResponse(
    String status,
    String pearlId,
    List<String> output
) { }
