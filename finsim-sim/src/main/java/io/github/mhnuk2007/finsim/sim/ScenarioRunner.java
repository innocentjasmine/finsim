package io.github.mhnuk2007.finsim.sim;

import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.AssertionsSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.BalanceAssertion;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.StepAction;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.StepSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.TraceExpectation;
import io.github.mhnuk2007.finsim.sim.ScenarioExecutor.StepOutcome;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Spring-independent orchestrator that runs declarative scenarios and verifies isolated replay
 * determinism using canonical trace normalization and strict {@link BigDecimal} assertions.
 */
public final class ScenarioRunner {

  public record ReplayReport(
      ScenarioRunResult initialRun,
      ScenarioRunResult replayRun,
      ReplayVerificationResult verification) {
    public boolean passed() {
      return initialRun.passed() && replayRun.passed() && verification.equivalent();
    }
  }

  private final Supplier<ScenarioExecutor> executorFactory;
  private final TraceNormalizer traceNormalizer;
  private final ReplayComparator replayComparator;

  public ScenarioRunner() {
    this(InMemoryScenarioEnvironment::new, new TraceNormalizer(), new ReplayComparator());
  }

  public ScenarioRunner(Supplier<ScenarioExecutor> executorFactory) {
    this(executorFactory, new TraceNormalizer(), new ReplayComparator());
  }

  public ScenarioRunner(
      Supplier<ScenarioExecutor> executorFactory,
      TraceNormalizer traceNormalizer,
      ReplayComparator replayComparator) {
    this.executorFactory =
        Objects.requireNonNull(executorFactory, "executorFactory must not be null");
    this.traceNormalizer =
        Objects.requireNonNull(traceNormalizer, "traceNormalizer must not be null");
    this.replayComparator =
        Objects.requireNonNull(replayComparator, "replayComparator must not be null");
  }

  /** Executes a scenario once in a fresh isolated environment and validates all assertions. */
  public ScenarioRunResult run(ScenarioDefinition scenario) {
    return runOn(scenario, executorFactory.get());
  }

  /**
   * Executes a scenario twice in two separate, freshly initialized environments and checks both run
   * assertions and canonical replay equivalence.
   */
  public ReplayReport runWithReplayVerification(ScenarioDefinition scenario) {
    return runWithReplayVerification(scenario, executorFactory);
  }

  /**
   * Executes an initial run using the default factory and a replay run using {@code
   * replayExecutorFactory}, useful for verifying divergence detection against modified fixtures.
   */
  public ReplayReport runWithReplayVerification(
      ScenarioDefinition scenario, Supplier<ScenarioExecutor> replayExecutorFactory) {
    ScenarioRunResult run1 = runOn(scenario, executorFactory.get());
    ScenarioRunResult run2 = runOn(scenario, replayExecutorFactory.get());
    ReplayVerificationResult verification = replayComparator.compare(run1, run2);
    return new ReplayReport(run1, run2, verification);
  }

  private ScenarioRunResult runOn(ScenarioDefinition scenario, ScenarioExecutor executor) {
    Objects.requireNonNull(scenario, "scenario must not be null");
    executor.initialize(scenario);

    List<StepSpec> orderedSteps =
        scenario.steps().stream().sorted(Comparator.comparingInt(StepSpec::step)).toList();

    List<StepOutcome> outcomes = new ArrayList<>(orderedSteps.size());
    List<String> failures = new ArrayList<>();
    Map<Integer, String> stepTxIds = new HashMap<>();
    String lastTransferTxId = null;

    for (StepSpec step : orderedSteps) {
      StepOutcome outcome = executeStep(scenario, executor, step, stepTxIds, lastTransferTxId);
      outcomes.add(outcome);
      if (step.action() == StepAction.TRANSFER) {
        stepTxIds.put(step.step(), outcome.transactionId());
        lastTransferTxId = outcome.transactionId();
      }
      verifyStepExpectations(step, outcome, failures);
    }

    Map<String, BigDecimal> finalBalances = executor.balances();
    List<TraceEvent> rawTrace = executor.trace();
    List<NormalizedTraceEvent> normalizedTrace = traceNormalizer.normalize(rawTrace);

    verifyAssertions(scenario.assertions(), finalBalances, rawTrace, failures);

    return new ScenarioRunResult(
        scenario.name(),
        scenario.seed(),
        failures.isEmpty(),
        outcomes,
        finalBalances,
        rawTrace,
        normalizedTrace,
        failures);
  }

  private static StepOutcome executeStep(
      ScenarioDefinition scenario,
      ScenarioExecutor executor,
      StepSpec step,
      Map<Integer, String> stepTxIds,
      String lastTransferTxId) {
    if (step.action() == StepAction.TRANSFER) {
      String txId =
          step.transactionId() != null
              ? step.transactionId()
              : DeterministicIdGenerator.derive(scenario.seed(), step.step());
      String idempKey =
          step.idempotencyKey() != null
              ? step.idempotencyKey()
              : DeterministicIdGenerator.derive(scenario.seed(), step.step());
      return executor.executeTransfer(step, txId, idempKey);
    }

    String targetTxId = step.transactionId();
    if (targetTxId == null && step.targetStep() != null) {
      targetTxId =
          stepTxIds.getOrDefault(
              step.targetStep(),
              DeterministicIdGenerator.derive(scenario.seed(), step.targetStep()));
    }
    if (targetTxId == null) {
      targetTxId = lastTransferTxId;
    }
    if (targetTxId == null) {
      throw new ScenarioValidationException(
          "Step %d RESOLVE could not determine target transactionId.".formatted(step.step()));
    }
    return executor.executeResolve(step, targetTxId);
  }

  public static void verifyStepExpectations(
      StepSpec step, StepOutcome outcome, List<String> failures) {
    if (step.expectedState() != null && !step.expectedState().equals(outcome.state())) {
      failures.add(
          "Step %d expected state '%s' but was '%s'"
              .formatted(step.step(), step.expectedState(), outcome.state()));
    }
    if (step.expectedPendingAction() != null
        && !step.expectedPendingAction().equals(outcome.pendingAction())) {
      failures.add(
          "Step %d expected pendingAction '%s' but was '%s'"
              .formatted(step.step(), step.expectedPendingAction(), outcome.pendingAction()));
    }
    if (step.expectedReasonCode() != null
        && !step.expectedReasonCode().equals(outcome.reasonCode())) {
      failures.add(
          "Step %d expected reasonCode '%s' but was '%s'"
              .formatted(step.step(), step.expectedReasonCode(), outcome.reasonCode()));
    }
  }

  private static void verifyAssertions(
      AssertionsSpec assertions,
      Map<String, BigDecimal> finalBalances,
      List<TraceEvent> rawTrace,
      List<String> failures) {
    for (BalanceAssertion bal : assertions.balances()) {
      String key = bal.account().qualified();
      BigDecimal actual = finalBalances.get(key);
      if (actual == null) {
        failures.add("Expected balance for missing account '%s'".formatted(key));
      } else if (actual.compareTo(bal.expectedBalance()) != 0) {
        failures.add(
            "Balance assertion failed for '%s': expected %s, actual %s"
                .formatted(key, bal.expectedBalance().toPlainString(), actual.toPlainString()));
      }
    }

    for (TraceExpectation expected : assertions.trace().mustIncludeEvents()) {
      boolean matched = rawTrace.stream().anyMatch(evt -> matchesTraceExpectation(expected, evt));
      if (!matched) {
        failures.add("Trace assertion failed: no event matched %s".formatted(expected));
      }
    }
  }

  public static boolean matchesTraceExpectation(TraceExpectation exp, TraceEvent evt) {
    if (!exp.kind().equals(evt.kind())) {
      return false;
    }
    if (exp.stage() != null && !exp.stage().equals(evt.stage())) {
      return false;
    }
    if (exp.target() != null && !exp.target().equals(evt.target())) {
      return false;
    }
    if (exp.faultId() != null && !exp.faultId().equals(evt.faultId())) {
      return false;
    }
    if (exp.fromState() != null && !exp.fromState().equals(evt.fromState())) {
      return false;
    }
    if (exp.toState() != null && !exp.toState().equals(evt.toState())) {
      return false;
    }
    return true;
  }
}
