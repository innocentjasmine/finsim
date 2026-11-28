package io.github.mhnuk2007.finsim.sim;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Execution port implemented by isolated simulation environments (in-memory in {@code finsim-sim})
 * or HTTP adapters (in {@code finsim-api} / {@code finsim-cli}).
 */
public interface ScenarioExecutor {

  /** Outcome of a single executed scenario step. */
  record StepOutcome(
      int step,
      ScenarioDefinition.StepAction action,
      String transactionId,
      String idempotencyKey,
      String state,
      String pendingAction,
      String reasonCode,
      boolean needsManualReview) {}

  void initialize(ScenarioDefinition scenario);

  StepOutcome executeTransfer(
      ScenarioDefinition.StepSpec step, String transactionId, String idempotencyKey);

  StepOutcome executeResolve(ScenarioDefinition.StepSpec step, String transactionId);

  default StepOutcome executeAdvanceTime(ScenarioDefinition.StepSpec step) {
    throw new UnsupportedOperationException(
        "ADVANCE_TIME is not supported by " + getClass().getSimpleName());
  }

  Map<String, BigDecimal> balances();

  List<TraceEvent> trace();
}
