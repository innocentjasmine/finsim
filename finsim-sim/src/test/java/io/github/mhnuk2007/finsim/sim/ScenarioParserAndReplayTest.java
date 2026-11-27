package io.github.mhnuk2007.finsim.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.StepAction;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.StepSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioRunner.ReplayReport;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenarioParserAndReplayTest {

  private final ScenarioParser parser = new ScenarioParser();
  private final ScenarioRunner runner = new ScenarioRunner();

  @Test
  void parsesAndReplaysLostCreditScenarioWithDeterminism() throws Exception {
    String yaml;
    try (InputStream in =
        getClass().getResourceAsStream("/scenarios/lost-credit-timeout-and-recovery.yaml")) {
      assertThat(in).isNotNull();
      yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    ScenarioDefinition scenario = parser.parseYaml(yaml);
    ReplayReport report = runner.runWithReplayVerification(scenario);

    assertThat(report.passed()).isTrue();
    assertThat(report.initialRun().assertionFailures()).isEmpty();
    assertThat(report.replayRun().assertionFailures()).isEmpty();
    assertThat(report.verification().equivalent()).isTrue();
    assertThat(report.verification().diffs()).isEmpty();
    assertThat(report.initialRun().finalBalances().get("bank-a:ACC-1"))
        .isEqualByComparingTo("450.00");
    assertThat(report.initialRun().finalBalances().get("bank-b:ACC-2"))
        .isEqualByComparingTo("150.00");
    assertThat(report.initialRun().steps()).hasSize(2);
    assertThat(report.initialRun().steps().get(0).state()).isEqualTo("UNKNOWN");
    assertThat(report.initialRun().steps().get(0).pendingAction()).isEqualTo("RETRY_CREDIT");
    assertThat(report.initialRun().steps().get(1).state()).isEqualTo("COMPLETED");
  }

  @Test
  void supportsJsonFormatAndPreservesExactBigDecimalPrecision() {
    String json =
        """
        {
          "version": "finsim/scenario/v1",
          "name": "fractional-precision-json",
          "network": "sandbox",
          "seed": 99,
          "participants": [
            {
              "id": "bank-a",
              "accounts": [{ "reference": "ACC-1", "initialBalance": "100.00000000", "currency": "USD" }]
            },
            {
              "id": "bank-b",
              "accounts": [{ "reference": "ACC-2", "initialBalance": "0.00000000", "currency": "USD" }]
            }
          ],
          "steps": [
            {
              "step": 1,
              "action": "TRANSFER",
              "from": "bank-a:ACC-1",
              "to": "bank-b:ACC-2",
              "amount": "0.12345678",
              "currency": "USD",
              "expectedState": "COMPLETED"
            }
          ],
          "assertions": {
            "balances": [
              { "account": "bank-a:ACC-1", "expectedBalance": "99.87654322" },
              { "account": "bank-b:ACC-2", "expectedBalance": "0.12345678" }
            ]
          }
        }
        """;

    ScenarioDefinition scenario = parser.parseJson(json);
    ReplayReport report = runner.runWithReplayVerification(scenario);

    assertThat(report.passed()).isTrue();
    String derived = DeterministicIdGenerator.derive(99L, 1);
    assertThat(report.initialRun().steps().get(0).transactionId()).isEqualTo(derived);
    assertThat(report.initialRun().steps().get(0).idempotencyKey()).isEqualTo(derived);
  }

  @Test
  void derivesDeterministicSha256IdsMatchingSpecification() throws Exception {
    long seed = 424242L;
    int stepIndex = 7;
    String expectedInput = "finsim:scenario:v1:" + seed + ":" + stepIndex;
    String expected =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(expectedInput.getBytes(StandardCharsets.UTF_8)))
            .substring(0, 32);

    assertThat(DeterministicIdGenerator.derive(seed, stepIndex)).isEqualTo(expected);
    assertThat(DeterministicIdGenerator.derive(seed, stepIndex)).hasSize(32);
    assertThat(DeterministicIdGenerator.derive(seed + 1, stepIndex)).isNotEqualTo(expected);
  }

  @Test
  void rejectsFloatingPointMonetaryNumbersAndMalformedSchemas() {
    String floatAmountYaml =
        """
        version: "finsim/scenario/v1"
        name: "bad-float"
        network: "sandbox"
        seed: 1
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
        steps:
          - step: 1
            action: "TRANSFER"
            from: "bank-a:ACC-1"
            to: "bank-b:ACC-2"
            amount: 50.0
            currency: "USD"
        """;

    assertThatThrownBy(() -> parser.parseYaml(floatAmountYaml))
        .isInstanceOf(ScenarioValidationException.class)
        .hasMessageContaining("quoted decimal string");

    String unknownFieldYaml =
        floatAmountYaml.replace("amount: 50.0", "amount: \"50.00\"\n    bogus: true");
    assertThatThrownBy(() -> parser.parseYaml(unknownFieldYaml))
        .isInstanceOf(ScenarioValidationException.class)
        .hasMessageContaining("Unknown field 'bogus'");
  }

  @Test
  void canonicalNormalizerExcludesWallClockLatencyThreadsAndEphemeralPorts() {
    TraceNormalizer normalizer = new TraceNormalizer();

    TraceEvent run1Event =
        new TraceEvent(
            "tx-1",
            Instant.parse("2026-11-27T10:00:00.123Z"),
            "OUTBOUND_CALL",
            "credit_destination",
            "bank-b",
            "POST",
            "http://localhost:51001/finsim/v1/payments",
            Map.of("amount", "50.00", "currency", "USD"),
            Map.of("status", "COMPLETED", "timestamp", "2026-11-27T10:00:00Z"),
            42L,
            null,
            null,
            null,
            101L);

    TraceEvent run2Event =
        new TraceEvent(
            "tx-1",
            Instant.parse("2026-11-28T23:59:59.999Z"),
            "OUTBOUND_CALL",
            "credit_destination",
            "bank-b",
            "post",
            "http://localhost:62999/finsim/v1/payments",
            Map.of("currency", "USD", "amount", "50.00"),
            Map.of("timestamp", "2026-11-28T23:59:59Z", "status", "COMPLETED"),
            980L,
            null,
            null,
            null,
            9999L);

    assertThat(normalizer.normalize(List.of(run1Event)))
        .isEqualTo(normalizer.normalize(List.of(run2Event)));
  }

  @Test
  void replayVerificationDetectsDivergentFixtureWithActionableDiff() {
    String yaml =
        """
        version: "finsim/scenario/v1"
        name: "divergence-check"
        network: "sandbox"
        seed: 777
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
            amount: "25.00"
            currency: "USD"
        """;

    ScenarioDefinition scenario = parser.parseYaml(yaml);
    ReplayReport divergent =
        runner.runWithReplayVerification(
            scenario,
            () ->
                new ScenarioExecutor() {
                  private final InMemoryScenarioEnvironment delegate =
                      new InMemoryScenarioEnvironment();

                  @Override
                  public void initialize(ScenarioDefinition s) {
                    // Deliberately alter replay balance fixture from 100.00 -> 10.00 so debit
                    // rejects AM04.
                    ScenarioDefinition mutated =
                        new ScenarioDefinition(
                            s.version(),
                            s.name(),
                            s.description(),
                            s.network(),
                            s.seed(),
                            List.of(
                                new ScenarioDefinition.ParticipantSpec(
                                    "bank-a",
                                    List.of(
                                        new ScenarioDefinition.AccountSpec(
                                            "ACC-1", new BigDecimal("10.00"), "USD", false))),
                                s.participants().get(1)),
                            s.faultRules(),
                            s.steps(),
                            s.assertions());
                    delegate.initialize(mutated);
                  }

                  @Override
                  public StepOutcome executeTransfer(
                      StepSpec step, String transactionId, String idempotencyKey) {
                    return delegate.executeTransfer(step, transactionId, idempotencyKey);
                  }

                  @Override
                  public StepOutcome executeResolve(StepSpec step, String transactionId) {
                    return delegate.executeResolve(step, transactionId);
                  }

                  @Override
                  public Map<String, BigDecimal> balances() {
                    return delegate.balances();
                  }

                  @Override
                  public List<TraceEvent> trace() {
                    return delegate.trace();
                  }
                });

    assertThat(divergent.passed()).isFalse();
    assertThat(divergent.verification().equivalent()).isFalse();
    assertThat(divergent.verification().diffs()).isNotEmpty();
    assertThat(divergent.verification().summary()).contains("diverged");
  }

  @Test
  void enforcesStepActionIsolationAndReversalOnCreditRejection() {
    String yaml =
        """
        version: "finsim/scenario/v1"
        name: "credit-rejected-reversal"
        network: "sandbox"
        seed: 555
        participants:
          - id: "bank-a"
            accounts:
              - reference: "ACC-1"
                initialBalance: "200.00"
                currency: "USD"
          - id: "bank-b"
            accounts:
              - reference: "ACC-CLOSED"
                initialBalance: "0.00"
                currency: "USD"
                closed: true
        steps:
          - step: 1
            action: "TRANSFER"
            from: "bank-a:ACC-1"
            to: "bank-b:ACC-CLOSED"
            amount: "40.00"
            currency: "USD"
            expectedState: "REJECTED"
            expectedReasonCode: "AC04"
        assertions:
          balances:
            - account: "bank-a:ACC-1"
              expectedBalance: "200.00"
            - account: "bank-b:ACC-CLOSED"
              expectedBalance: "0.00"
        """;

    ScenarioDefinition scenario = parser.parseYaml(yaml);
    assertThat(scenario.steps().get(0).action()).isEqualTo(StepAction.TRANSFER);
    ReplayReport report = runner.runWithReplayVerification(scenario);
    assertThat(report.passed()).isTrue();
  }
}
