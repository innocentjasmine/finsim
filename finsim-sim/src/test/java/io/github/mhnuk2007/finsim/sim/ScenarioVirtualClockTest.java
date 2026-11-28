package io.github.mhnuk2007.finsim.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.AccountEndpoint;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.AccountSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.ParticipantSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.StepAction;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.StepSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioRunner.ReplayReport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScenarioVirtualClockTest {

  private final ScenarioParser parser = new ScenarioParser();

  @Test
  void advanceTimeScenarioReplaysDeterministicallyAndIsolatesTwoClocks() {
    String yaml =
        """
        version: "finsim/scenario/v1"
        name: "time-travel-replay"
        network: "sandbox"
        seed: 77
        startTime: "2026-01-01T00:00:00Z"
        participants:
          - id: "bank-a"
            accounts:
              - reference: "ACC-1"
                initialBalance: "500.00"
                currency: "USD"
          - id: "bank-b"
            accounts:
              - reference: "ACC-2"
                initialBalance: "100.00"
                currency: "USD"
        faultRules:
          - id: "delay-credit"
            target: "bank-b"
            stage: "credit_destination"
            type: "DELAY"
            delayMs: 2
            times: 1
        steps:
          - step: 1
            action: "TRANSFER"
            from: "bank-a:ACC-1"
            to: "bank-b:ACC-2"
            amount: "50.00"
            currency: "USD"
            expectedState: "COMPLETED"
          - step: 2
            action: "ADVANCE_TIME"
            advanceBy: "P7D"
            expectedState: "ADVANCED"
          - step: 3
            action: "ADVANCE_TIME"
            advanceTo: "2026-02-01T00:00:00Z"
            expectedState: "ADVANCED"
          - step: 4
            action: "TRANSFER"
            from: "bank-b:ACC-2"
            to: "bank-a:ACC-1"
            amount: "20.00"
            currency: "USD"
            expectedState: "COMPLETED"
        assertions:
          balances:
            - account: "bank-a:ACC-1"
              expectedBalance: "470.00"
            - account: "bank-b:ACC-2"
              expectedBalance: "130.00"
          trace:
            mustIncludeEvents:
              - kind: "TIME_ADVANCED"
                stage: "advance_time"
                target: "virtual-clock"
        """;

    ScenarioDefinition scenario = parser.parseYaml(yaml);
    Instant wallBefore = Instant.now().minusSeconds(1);
    ReplayReport report = new ScenarioRunner().runWithReplayVerification(scenario);
    Instant wallAfter = Instant.now().plusSeconds(1);

    assertThat(report.passed()).isTrue();
    assertThat(report.verification().equivalent()).isTrue();
    assertThat(report.initialRun().normalizedTrace())
        .anySatisfy(
            e -> {
              assertThat(e.kind()).isEqualTo("TIME_ADVANCED");
              assertThat(e.virtualAt()).isEqualTo(Instant.parse("2026-01-08T00:00:00Z"));
            })
        .anySatisfy(
            e -> {
              assertThat(e.kind()).isEqualTo("TIME_ADVANCED");
              assertThat(e.virtualAt()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
            });

    // Wall-clock durationMs remains unaffected by the 31-day virtual time travel.
    assertThat(report.initialRun().rawTrace())
        .allSatisfy(
            e -> {
              assertThat(e.at()).isBetween(wallBefore, wallAfter);
              if (e.durationMs() != null) {
                assertThat(e.durationMs()).isLessThan(1_000L);
              }
            });
  }

  @Test
  void replayComparatorDetectsVirtualTimestampDivergenceAcrossRuns() {
    String baseYaml =
        """
        version: "finsim/scenario/v1"
        name: "clock-divergence"
        network: "sandbox"
        seed: 9
        startTime: "%s"
        participants:
          - id: "bank-a"
            accounts:
              - reference: "ACC-1"
                initialBalance: "100.00"
                currency: "USD"
          - id: "bank-b"
            accounts:
              - reference: "ACC-2"
                initialBalance: "0.00"
                currency: "USD"
        steps:
          - step: 1
            action: "TRANSFER"
            from: "bank-a:ACC-1"
            to: "bank-b:ACC-2"
            amount: "10.00"
            currency: "USD"
        """;

    ScenarioRunner runner = new ScenarioRunner();
    ScenarioRunResult run1 =
        runner.run(parser.parseYaml(baseYaml.formatted("2026-01-01T00:00:00Z")));
    ScenarioRunResult run2 =
        runner.run(parser.parseYaml(baseYaml.formatted("2026-01-02T00:00:00Z")));

    ReplayVerificationResult comparison = new ReplayComparator().compare(run1, run2);
    assertThat(comparison.equivalent()).isFalse();
    assertThat(comparison.diffs()).anyMatch(diff -> diff.contains(".virtualAt diverged"));
  }

  @Test
  void omittedStartTimeDefaultsToInstantEpochAndJsonAdvanceTimeParsesCleanly() {
    String json =
        """
        {
          "version": "finsim/scenario/v1",
          "name": "json-epoch-default",
          "network": "sandbox",
          "seed": 5,
          "participants": [
            { "id": "p1", "accounts": [ { "reference": "A1", "initialBalance": "10.00", "currency": "USD" } ] }
          ],
          "steps": [
            { "step": 1, "action": "ADVANCE_TIME", "advanceBy": "PT45M" }
          ]
        }
        """;

    ScenarioDefinition parsed = parser.parseJson(json);
    assertThat(parsed.startTime()).isNull();
    assertThat(parsed.effectiveStartTime()).isEqualTo(Instant.EPOCH);

    ScenarioRunResult result = new ScenarioRunner().run(parsed);
    assertThat(result.passed()).isTrue();
    assertThat(result.normalizedTrace())
        .anySatisfy(e -> assertThat(e.virtualAt()).isEqualTo(Instant.EPOCH.plusSeconds(45 * 60L)));
  }

  @Test
  void invalidAdvanceTimeAndStartTimeInputsThrowScenarioValidationException() {
    String template =
        """
        version: "finsim/scenario/v1"
        name: "invalid-time"
        network: "sandbox"
        seed: 1
        %s
        participants:
          - id: "p1"
            accounts:
              - reference: "A1"
                initialBalance: "100.00"
                currency: "USD"
          - id: "p2"
            accounts:
              - reference: "A2"
                initialBalance: "100.00"
                currency: "USD"
        steps:
          %s
        """;

    // Both advanceBy and advanceTo specified
    assertThatThrownBy(
            () ->
                parser.parseYaml(
                    template.formatted(
                        "",
                        "- step: 1\n    action: \"ADVANCE_TIME\"\n    advanceBy: \"PT1M\"\n    advanceTo: \"2026-01-01T00:00:00Z\"")))
        .isInstanceOf(ScenarioValidationException.class)
        .hasMessageContaining("exactly one of");

    // Neither advanceBy nor advanceTo specified
    assertThatThrownBy(
            () ->
                parser.parseYaml(template.formatted("", "- step: 1\n    action: \"ADVANCE_TIME\"")))
        .isInstanceOf(ScenarioValidationException.class)
        .hasMessageContaining("exactly one of");

    // Zero or negative advanceBy
    assertThatThrownBy(
            () ->
                parser.parseYaml(
                    template.formatted(
                        "", "- step: 1\n    action: \"ADVANCE_TIME\"\n    advanceBy: \"PT0S\"")))
        .isInstanceOf(ScenarioValidationException.class)
        .hasMessageContaining("strictly positive");

    // Backward advanceTo relative to startTime
    assertThatThrownBy(
            () ->
                parser.parseYaml(
                    template.formatted(
                        "startTime: \"2026-03-01T00:00:00Z\"",
                        "- step: 1\n    action: \"ADVANCE_TIME\"\n    advanceTo: \"2026-02-28T23:59:59Z\"")))
        .isInstanceOf(ScenarioValidationException.class)
        .hasMessageContaining("precedes current virtual time");

    // Conflicting transfer fields on ADVANCE_TIME
    assertThatThrownBy(
            () ->
                parser.parseYaml(
                    template.formatted(
                        "",
                        "- step: 1\n    action: \"ADVANCE_TIME\"\n    advanceBy: \"PT1S\"\n    from: \"p1:A1\"")))
        .isInstanceOf(ScenarioValidationException.class);

    // TRANSFER must not carry advanceBy
    assertThatThrownBy(
            () ->
                parser.parseYaml(
                    template.formatted(
                        "",
                        "- step: 1\n    action: \"TRANSFER\"\n    from: \"p1:A1\"\n    to: \"p2:A2\"\n    amount: \"1.00\"\n    currency: \"USD\"\n    advanceBy: \"PT1S\"")))
        .isInstanceOf(ScenarioValidationException.class);

    // Malformed ISO instant / duration
    assertThatThrownBy(
            () ->
                parser.parseYaml(
                    template.formatted(
                        "startTime: \"not-an-instant\"",
                        "- step: 1\n    action: \"ADVANCE_TIME\"\n    advanceBy: \"PT1S\"")))
        .isInstanceOf(ScenarioValidationException.class);
    assertThatThrownBy(
            () ->
                parser.parseYaml(
                    template.formatted(
                        "", "- step: 1\n    action: \"ADVANCE_TIME\"\n    advanceBy: \"5m\"")))
        .isInstanceOf(ScenarioValidationException.class);
  }

  @Test
  void programmaticBackwardAdvanceToAndUnsupportedExecutorGuardFailCleanly() {
    ParticipantSpec p1 =
        new ParticipantSpec(
            "p1", List.of(new AccountSpec("A1", new BigDecimal("10.00"), "USD", false)));
    StepSpec backwardStep =
        new StepSpec(
            1,
            StepAction.ADVANCE_TIME,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Instant.parse("2025-12-31T00:00:00Z"));
    ScenarioDefinition scenario =
        new ScenarioDefinition(
            ScenarioDefinition.SUPPORTED_VERSION,
            "runtime-backward",
            null,
            "sandbox",
            1L,
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(p1),
            List.of(),
            List.of(backwardStep),
            null);

    assertThatThrownBy(() -> new ScenarioRunner().run(scenario))
        .isInstanceOf(ScenarioValidationException.class)
        .hasMessageContaining("ADVANCE_TIME invalid");

    ScenarioExecutor customWithoutTimeTravel =
        new ScenarioExecutor() {
          @Override
          public void initialize(ScenarioDefinition s) {}

          @Override
          public StepOutcome executeTransfer(StepSpec step, String txId, String key) {
            return null;
          }

          @Override
          public StepOutcome executeResolve(StepSpec step, String txId) {
            return null;
          }

          @Override
          public java.util.Map<String, BigDecimal> balances() {
            return java.util.Map.of();
          }

          @Override
          public List<TraceEvent> trace() {
            return List.of();
          }
        };

    StepSpec forwardStep =
        new StepSpec(
            1,
            StepAction.ADVANCE_TIME,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Duration.ofSeconds(10),
            null);
    assertThatThrownBy(() -> customWithoutTimeTravel.executeAdvanceTime(forwardStep))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(new AccountEndpoint("p1", "A1").qualified()).isEqualTo("p1:A1");
  }
}
