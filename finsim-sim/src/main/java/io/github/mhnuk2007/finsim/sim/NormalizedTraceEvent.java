package io.github.mhnuk2007.finsim.sim;

/**
 * Deterministic canonical view of a {@link TraceEvent}, excluding wall-clock timestamps, elapsed
 * latency durations, ephemeral socket ports, and JVM thread identifiers.
 */
public record NormalizedTraceEvent(
    int sequenceIndex,
    String transferId,
    String kind,
    String stage,
    String target,
    String requestMethod,
    String requestPath,
    String requestPayloadHash,
    String responsePayloadHash,
    String faultId,
    String fromState,
    String toState) {}
