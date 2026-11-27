package io.github.mhnuk2007.finsim.sim;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Compares two {@link ScenarioRunResult} executions for canonical trace equivalence and exact
 * {@link BigDecimal} participant balance identity.
 */
public final class ReplayComparator {

  private final TraceNormalizer normalizer;

  public ReplayComparator() {
    this(new TraceNormalizer());
  }

  public ReplayComparator(TraceNormalizer normalizer) {
    this.normalizer = Objects.requireNonNull(normalizer, "normalizer must not be null");
  }

  public ReplayVerificationResult compare(ScenarioRunResult run1, ScenarioRunResult run2) {
    Objects.requireNonNull(run1, "run1 must not be null");
    Objects.requireNonNull(run2, "run2 must not be null");

    List<NormalizedTraceEvent> norm1 = normalizer.normalize(run1.rawTrace());
    List<NormalizedTraceEvent> norm2 = normalizer.normalize(run2.rawTrace());

    List<String> diffs = new ArrayList<>();
    compareTraces(norm1, norm2, diffs);
    compareBalances(run1.finalBalances(), run2.finalBalances(), diffs);

    return new ReplayVerificationResult(diffs.isEmpty(), diffs, norm1, norm2);
  }

  private static void compareTraces(
      List<NormalizedTraceEvent> norm1, List<NormalizedTraceEvent> norm2, List<String> diffs) {
    if (norm1.size() != norm2.size()) {
      diffs.add(
          "Normalized trace length mismatch: run1=%d events, run2=%d events"
              .formatted(norm1.size(), norm2.size()));
    }
    int minSize = Math.min(norm1.size(), norm2.size());
    for (int i = 0; i < minSize; i++) {
      NormalizedTraceEvent e1 = norm1.get(i);
      NormalizedTraceEvent e2 = norm2.get(i);
      if (!e1.equals(e2)) {
        compareEventFields(i, e1, e2, diffs);
      }
    }
    for (int i = minSize; i < norm1.size(); i++) {
      diffs.add("Trace[%d] extra event in run1: %s".formatted(i, norm1.get(i)));
    }
    for (int i = minSize; i < norm2.size(); i++) {
      diffs.add("Trace[%d] extra event in run2: %s".formatted(i, norm2.get(i)));
    }
  }

  private static void compareEventFields(
      int index, NormalizedTraceEvent e1, NormalizedTraceEvent e2, List<String> diffs) {
    diffField(index, "transferId", e1.transferId(), e2.transferId(), diffs);
    diffField(index, "kind", e1.kind(), e2.kind(), diffs);
    diffField(index, "stage", e1.stage(), e2.stage(), diffs);
    diffField(index, "target", e1.target(), e2.target(), diffs);
    diffField(index, "requestMethod", e1.requestMethod(), e2.requestMethod(), diffs);
    diffField(index, "requestPath", e1.requestPath(), e2.requestPath(), diffs);
    diffField(index, "requestPayloadHash", e1.requestPayloadHash(), e2.requestPayloadHash(), diffs);
    diffField(
        index, "responsePayloadHash", e1.responsePayloadHash(), e2.responsePayloadHash(), diffs);
    diffField(index, "faultId", e1.faultId(), e2.faultId(), diffs);
    diffField(index, "fromState", e1.fromState(), e2.fromState(), diffs);
    diffField(index, "toState", e1.toState(), e2.toState(), diffs);
  }

  private static void diffField(int index, String field, Object v1, Object v2, List<String> diffs) {
    if (!Objects.equals(v1, v2)) {
      diffs.add("Trace[%d].%s diverged: run1='%s' vs run2='%s'".formatted(index, field, v1, v2));
    }
  }

  private static void compareBalances(
      Map<String, BigDecimal> balances1, Map<String, BigDecimal> balances2, List<String> diffs) {
    TreeSet<String> allAccounts = new TreeSet<>(balances1.keySet());
    allAccounts.addAll(balances2.keySet());
    for (String account : allAccounts) {
      BigDecimal b1 = balances1.get(account);
      BigDecimal b2 = balances2.get(account);
      if (b1 == null || b2 == null) {
        diffs.add(
            "Balance presence diverged for '%s': run1=%s vs run2=%s"
                .formatted(account, format(b1), format(b2)));
      } else if (b1.compareTo(b2) != 0) {
        diffs.add(
            "Final balance diverged for '%s': run1=%s vs run2=%s"
                .formatted(account, b1.toPlainString(), b2.toPlainString()));
      }
    }
  }

  private static String format(BigDecimal value) {
    return value != null ? value.toPlainString() : "<missing>";
  }
}
