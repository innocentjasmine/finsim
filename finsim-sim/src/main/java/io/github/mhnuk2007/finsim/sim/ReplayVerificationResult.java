package io.github.mhnuk2007.finsim.sim;

import java.util.List;

/** Outcome of comparing an original scenario execution against an isolated replay execution. */
public record ReplayVerificationResult(
    boolean equivalent,
    List<String> diffs,
    List<NormalizedTraceEvent> normalizedRun1,
    List<NormalizedTraceEvent> normalizedRun2) {

  public ReplayVerificationResult {
    diffs = diffs != null ? List.copyOf(diffs) : List.of();
    normalizedRun1 = normalizedRun1 != null ? List.copyOf(normalizedRun1) : List.of();
    normalizedRun2 = normalizedRun2 != null ? List.copyOf(normalizedRun2) : List.of();
  }

  /** Renders an actionable human-readable diff report when equivalence fails. */
  public String summary() {
    if (equivalent) {
      return "Replay verification PASSED (%d normalized trace events matched identically)."
          .formatted(normalizedRun1.size());
    }
    StringBuilder sb =
        new StringBuilder("Replay verification FAILED with ")
            .append(diffs.size())
            .append(" divergence(s):");
    for (String diff : diffs) {
      sb.append("\n  - ").append(diff);
    }
    return sb.toString();
  }
}
