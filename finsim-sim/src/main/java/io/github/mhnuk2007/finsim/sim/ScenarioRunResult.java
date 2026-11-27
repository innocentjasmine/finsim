package io.github.mhnuk2007.finsim.sim;

import io.github.mhnuk2007.finsim.sim.ScenarioExecutor.StepOutcome;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Complete result of running a {@link ScenarioDefinition} once, including assertions. */
public record ScenarioRunResult(
    String scenarioName,
    long seed,
    boolean passed,
    List<StepOutcome> steps,
    Map<String, BigDecimal> finalBalances,
    List<TraceEvent> rawTrace,
    List<NormalizedTraceEvent> normalizedTrace,
    List<String> assertionFailures) {

  public ScenarioRunResult {
    steps = steps != null ? List.copyOf(steps) : List.of();
    finalBalances = finalBalances != null ? Map.copyOf(finalBalances) : Map.of();
    rawTrace = rawTrace != null ? List.copyOf(rawTrace) : List.of();
    normalizedTrace = normalizedTrace != null ? List.copyOf(normalizedTrace) : List.of();
    assertionFailures = assertionFailures != null ? List.copyOf(assertionFailures) : List.of();
  }
}
