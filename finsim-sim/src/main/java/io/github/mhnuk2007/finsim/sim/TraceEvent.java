package io.github.mhnuk2007.finsim.sim;

import java.time.Instant;

/**
 * Raw execution trace event captured during a scenario run, including non-deterministic runtime
 * metadata (wall-clock timestamp, latency duration, JVM thread id) that {@link TraceNormalizer}
 * strips for replay comparisons.
 */
public record TraceEvent(
    String transferId,
    Instant at,
    String kind,
    String stage,
    String target,
    String httpMethod,
    String requestPath,
    Object request,
    Object response,
    Long durationMs,
    String faultId,
    String fromState,
    String toState,
    Long threadId) {

  public static TraceEvent stage(String transferId, String stage, Instant at) {
    return new TraceEvent(
        transferId,
        at,
        "STAGE",
        stage,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Thread.currentThread().threadId());
  }

  public static TraceEvent stateChange(
      String transferId, String stage, String fromState, String toState, Instant at) {
    return new TraceEvent(
        transferId,
        at,
        "STATE_CHANGE",
        stage,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        fromState,
        toState,
        Thread.currentThread().threadId());
  }

  public static TraceEvent faultInjected(
      String transferId, String stage, String target, String faultId, Instant at) {
    return new TraceEvent(
        transferId,
        at,
        "FAULT_INJECTED",
        stage,
        target,
        null,
        null,
        null,
        null,
        null,
        faultId,
        null,
        null,
        Thread.currentThread().threadId());
  }

  public static TraceEvent outboundCall(
      String transferId,
      String stage,
      String target,
      String httpMethod,
      String requestPath,
      Object request,
      Object response,
      Long durationMs,
      Instant at) {
    return new TraceEvent(
        transferId,
        at,
        "OUTBOUND_CALL",
        stage,
        target,
        httpMethod,
        requestPath,
        request,
        response,
        durationMs,
        null,
        null,
        null,
        Thread.currentThread().threadId());
  }
}
