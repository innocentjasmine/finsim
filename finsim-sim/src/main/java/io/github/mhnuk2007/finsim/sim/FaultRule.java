package io.github.mhnuk2007.finsim.sim;

import java.util.Objects;

/** An immutable rule specifying a simulated fault to inject on outbound calls to a participant. */
public record FaultRule(
    String faultId,
    String target,
    FaultType type,
    Integer delayMs,
    double probability,
    Integer remainingCount,
    Long seed,
    String stage,
    FaultBehavior behavior) {

  public FaultRule {
    Objects.requireNonNull(faultId, "faultId must not be null");
    if (faultId.isBlank()) {
      throw new IllegalArgumentException("faultId must not be blank");
    }
    Objects.requireNonNull(target, "target must not be null");
    if (target.isBlank()) {
      throw new IllegalArgumentException("target must not be blank");
    }
    Objects.requireNonNull(type, "type must not be null");

    if (type == FaultType.DELAY) {
      if (delayMs == null || delayMs < 0) {
        throw new IllegalArgumentException("delayMs must be non-negative for DELAY fault type");
      }
    }

    if (probability < 0.0 || probability > 1.0) {
      throw new IllegalArgumentException("probability must be between 0.0 and 1.0 inclusive");
    }

    if (remainingCount != null && remainingCount < 0) {
      throw new IllegalArgumentException("remainingCount must not be negative");
    }

    stage = (stage != null && !stage.isBlank()) ? stage.trim() : null;
    behavior = behavior != null ? behavior : FaultBehavior.BEFORE_PROCESSING;

    if (behavior == FaultBehavior.RESPONSE_LOST_AFTER_PROCESSING && type != FaultType.TIMEOUT) {
      throw new IllegalArgumentException(
          "RESPONSE_LOST_AFTER_PROCESSING behavior is only supported for TIMEOUT faults");
    }
  }

  /**
   * Backward-compatible constructor defaulting stage to all stages and behavior before delivery.
   */
  public FaultRule(
      String faultId,
      String target,
      FaultType type,
      Integer delayMs,
      double probability,
      Integer remainingCount,
      Long seed) {
    this(
        faultId,
        target,
        type,
        delayMs,
        probability,
        remainingCount,
        seed,
        null,
        FaultBehavior.BEFORE_PROCESSING);
  }

  public boolean isActive() {
    return remainingCount == null || remainingCount > 0;
  }

  public FaultRule withRemainingCount(Integer newCount) {
    return new FaultRule(
        faultId, target, type, delayMs, probability, newCount, seed, stage, behavior);
  }
}
