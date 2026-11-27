package io.github.mhnuk2007.finsim.cli;

import io.github.mhnuk2007.finsim.sim.FaultBehavior;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.FaultRuleSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.StepAction;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.StepSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.TraceExpectation;
import io.github.mhnuk2007.finsim.sim.ScenarioExecutor.StepOutcome;
import io.github.mhnuk2007.finsim.sim.ScenarioParser;
import io.github.mhnuk2007.finsim.sim.ScenarioRunResult;
import io.github.mhnuk2007.finsim.sim.ScenarioRunner;
import io.github.mhnuk2007.finsim.sim.ScenarioRunner.ReplayReport;
import io.github.mhnuk2007.finsim.sim.ScenarioValidationException;
import io.github.mhnuk2007.finsim.sim.TraceEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Declarative scenario execution and deterministic replay commands. */
@Command(
    name = "scenario",
    mixinStandardHelpOptions = true,
    description = "Run declarative simulation scenarios and verify deterministic replay.",
    subcommands = {ScenarioCommand.Run.class})
final class ScenarioCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  @Command(
      name = "run",
      mixinStandardHelpOptions = true,
      description = "Execute a declarative scenario.v1 YAML/JSON file.")
  static class Run implements Callable<Integer> {

    @Mixin FinSimCli.Connection connection = new FinSimCli.Connection();
    @Spec CommandSpec spec;

    @Parameters(index = "0", description = "Path to a finsim/scenario/v1 YAML or JSON file.")
    Path scenarioFile;

    @Option(
        names = "--verify-replay",
        description = "Re-run in a fresh isolated environment and verify trace & balance equality.")
    boolean verifyReplay;

    @Option(
        names = "--live",
        description = "Execute single-pass scenario steps against a live FinSim server.")
    boolean live;

    @Option(
        names = {"--json", "-j"},
        description = "Output machine-readable JSON.")
    boolean jsonMode;

    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      PrintWriter err = spec.commandLine().getErr();

      if (live && verifyReplay) {
        err.println(
            "--verify-replay requires isolated execution environments and cannot be combined with --live.");
        return CommandLine.ExitCode.USAGE;
      }

      ScenarioDefinition scenario;
      try {
        scenario = new ScenarioParser().parse(scenarioFile);
      } catch (java.nio.file.NoSuchFileException e) {
        err.println("Scenario file not found: " + scenarioFile.toAbsolutePath().normalize());
        return CommandLine.ExitCode.USAGE;
      } catch (IOException | ScenarioValidationException e) {
        err.println("Invalid scenario: " + e.getMessage());
        return CommandLine.ExitCode.USAGE;
      }

      if (live) {
        return runLive(scenario, out, err);
      }

      ScenarioRunner runner = new ScenarioRunner();
      if (verifyReplay) {
        ReplayReport report = runner.runWithReplayVerification(scenario);
        printReplayReport(report, out);
        return report.passed() ? 0 : FinSimCli.EXIT_FAILED;
      }

      ScenarioRunResult result = runner.run(scenario);
      printSingleRun(result, out);
      return result.passed() ? 0 : FinSimCli.EXIT_FAILED;
    }

    private Integer runLive(ScenarioDefinition scenario, PrintWriter out, PrintWriter err) {
      for (FaultRuleSpec rule : scenario.faultRules()) {
        if (rule.stage() != null
            || rule.behavior() == FaultBehavior.RESPONSE_LOST_AFTER_PROCESSING) {
          err.println(
              "Fault rule '"
                  + rule.id()
                  + "' uses stage targeting or RESPONSE_LOST_AFTER_PROCESSING, which is only supported in isolated scenario runs.");
          return CommandLine.ExitCode.USAGE;
        }
      }

      FinSimClient client = connection.client();
      List<String> createdFaultIds = new ArrayList<>();
      Map<String, String> faultIdAliases = new LinkedHashMap<>();
      List<StepOutcome> outcomes = new ArrayList<>();
      List<String> failures = new ArrayList<>();
      List<TraceEvent> collectedTrace = new ArrayList<>();

      try {
        for (FaultRuleSpec rule : scenario.faultRules()) {
          StringBuilder body = new StringBuilder("{");
          body.append("\"target\":")
              .append(Json.quote(rule.target()))
              .append(",\"type\":")
              .append(Json.quote(rule.type().name()));
          if (rule.delayMs() != null) {
            body.append(",\"delayMs\":").append(rule.delayMs());
          }
          if (rule.probability() != null) {
            body.append(",\"probability\":").append(rule.probability().toPlainString());
          }
          if (rule.times() != null) {
            body.append(",\"times\":").append(rule.times());
          }
          body.append(",\"seed\":").append(rule.seed() != null ? rule.seed() : scenario.seed());
          body.append("}");

          FinSimClient.Response res = client.post("/api/v1/faults", body.toString());
          if (!res.ok()) {
            err.println(
                "Failed to register fault rule '"
                    + rule.id()
                    + "' (HTTP "
                    + res.status()
                    + "): "
                    + res.body());
            return FinSimCli.EXIT_FAILED;
          }
          String createdFaultId = extractFaultId(res.body(), rule.id());
          createdFaultIds.add(createdFaultId);
          faultIdAliases.put(rule.id(), createdFaultId);
        }

        List<StepSpec> orderedSteps =
            scenario.steps().stream().sorted(Comparator.comparingInt(StepSpec::step)).toList();
        String lastTxId = null;

        for (StepSpec step : orderedSteps) {
          if (step.action() == StepAction.TRANSFER) {
            String ref = step.reference() != null ? step.reference() : "SCENARIO-" + step.step();
            String jsonBody =
                "{\"from\":{\"institutionId\":"
                    + Json.quote(step.from().institutionId())
                    + ",\"accountReference\":"
                    + Json.quote(step.from().accountReference())
                    + "},\"to\":{\"institutionId\":"
                    + Json.quote(step.to().institutionId())
                    + ",\"accountReference\":"
                    + Json.quote(step.to().accountReference())
                    + "},\"money\":{\"amount\":"
                    + Json.quote(step.amount().toPlainString())
                    + ",\"currency\":"
                    + Json.quote(step.currency())
                    + "},\"reference\":"
                    + Json.quote(ref)
                    + "}";
            FinSimClient.Response res =
                client.post(
                    "/api/v1/networks/" + scenario.network() + "/transfers",
                    jsonBody,
                    step.idempotencyKey());
            if (!res.ok()) {
              err.println(
                  "Step " + step.step() + " failed HTTP " + res.status() + ": " + res.body());
              return FinSimCli.EXIT_FAILED;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) ContractJson.parse(res.body());
            StepOutcome outcome = toStepOutcome(step, step.idempotencyKey(), parsed);
            outcomes.add(outcome);
            lastTxId = outcome.transactionId();
            ScenarioRunner.verifyStepExpectations(step, outcome, failures);
            fetchTraceEvents(client, lastTxId, collectedTrace);
          } else {
            String txId = step.transactionId() != null ? step.transactionId() : lastTxId;
            if (txId == null) {
              err.println("Step " + step.step() + " RESOLVE could not determine transactionId.");
              return CommandLine.ExitCode.USAGE;
            }
            FinSimClient.Response res = client.post("/api/v1/transfers/" + txId + "/resolve");
            if (!res.ok()) {
              err.println(
                  "Step " + step.step() + " resolve HTTP " + res.status() + ": " + res.body());
              return FinSimCli.EXIT_FAILED;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) ContractJson.parse(res.body());
            StepOutcome outcome = toStepOutcome(step, null, parsed);
            outcomes.add(outcome);
            ScenarioRunner.verifyStepExpectations(step, outcome, failures);
            collectedTrace.clear();
            fetchTraceEvents(client, txId, collectedTrace);
          }
        }

        for (TraceExpectation exp : scenario.assertions().trace().mustIncludeEvents()) {
          TraceExpectation resolvedExp =
              exp.faultId() != null && faultIdAliases.containsKey(exp.faultId())
                  ? new TraceExpectation(
                      exp.kind(),
                      exp.stage(),
                      exp.target(),
                      faultIdAliases.get(exp.faultId()),
                      exp.fromState(),
                      exp.toState())
                  : exp;
          boolean matched =
              collectedTrace.stream()
                  .anyMatch(evt -> ScenarioRunner.matchesTraceExpectation(resolvedExp, evt));
          if (!matched) {
            failures.add("Trace assertion failed: no event matched " + exp);
          }
        }

        ScenarioRunResult liveResult =
            new ScenarioRunResult(
                scenario.name(),
                scenario.seed(),
                failures.isEmpty(),
                outcomes,
                Map.of(),
                collectedTrace,
                List.of(),
                failures);
        printSingleRun(liveResult, out);
        return liveResult.passed() ? 0 : FinSimCli.EXIT_FAILED;
      } catch (IOException e) {
        err.println("Cannot reach FinSim at " + client.baseUrl() + ": " + e.getMessage());
        return FinSimCli.EXIT_FAILED;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return FinSimCli.EXIT_FAILED;
      } finally {
        for (String faultId : createdFaultIds) {
          try {
            client.delete("/api/v1/faults/" + faultId);
          } catch (Exception ignored) {
            // Best-effort cleanup of scenario fault rule on exit.
          }
        }
      }
    }

    private void printSingleRun(ScenarioRunResult result, PrintWriter out) {
      if (jsonMode) {
        out.println(ContractJson.write(runToMap(result, null)));
        return;
      }
      out.println("Scenario: " + result.scenarioName() + " (seed=" + result.seed() + ")");
      printStepsAndTrace(result, out);
      if (!result.finalBalances().isEmpty()) {
        out.println("Final balances:");
        result
            .finalBalances()
            .forEach((acc, bal) -> out.println("  " + acc + " = " + bal.toPlainString()));
      }
      printFailures(result.assertionFailures(), out);
      out.println(result.passed() ? "Scenario PASSED." : "Scenario FAILED.");
    }

    private void printReplayReport(ReplayReport report, PrintWriter out) {
      ScenarioRunResult run1 = report.initialRun();
      if (jsonMode) {
        out.println(ContractJson.write(runToMap(run1, report)));
        return;
      }
      out.println("Scenario: " + run1.scenarioName() + " (seed=" + run1.seed() + ")");
      printStepsAndTrace(run1, out);
      out.println("Final balances:");
      run1.finalBalances()
          .forEach((acc, bal) -> out.println("  " + acc + " = " + bal.toPlainString()));
      printFailures(run1.assertionFailures(), out);
      out.println(report.verification().summary());
      if (!report.replayRun().assertionFailures().isEmpty()) {
        out.println("Replay run assertion failures:");
        for (String fail : report.replayRun().assertionFailures()) {
          out.println("  - " + fail);
        }
      }
      out.println(
          report.passed()
              ? "Scenario & Replay Verification PASSED."
              : "Scenario Verification FAILED.");
    }

    private static void printStepsAndTrace(ScenarioRunResult result, PrintWriter out) {
      for (StepOutcome step : result.steps()) {
        out.println(
            "  [Step "
                + step.step()
                + "] "
                + step.action()
                + " tx="
                + step.transactionId()
                + " -> state="
                + step.state()
                + (step.pendingAction() != null && !"NONE".equals(step.pendingAction())
                    ? " (pendingAction=" + step.pendingAction() + ")"
                    : ""));
      }
      List<TraceEvent> highlights =
          result.rawTrace().stream()
              .filter(e -> "FAULT_INJECTED".equals(e.kind()) || "STATE_CHANGE".equals(e.kind()))
              .toList();
      if (!highlights.isEmpty()) {
        out.println("Key trace events:");
        for (TraceEvent evt : highlights) {
          if ("FAULT_INJECTED".equals(evt.kind())) {
            out.println(
                "  - FAULT_INJECTED stage="
                    + evt.stage()
                    + " target="
                    + evt.target()
                    + " faultId="
                    + evt.faultId());
          } else {
            out.println(
                "  - STATE_CHANGE stage="
                    + evt.stage()
                    + " "
                    + evt.fromState()
                    + " -> "
                    + evt.toState());
          }
        }
      }
    }

    private static void printFailures(List<String> failures, PrintWriter out) {
      if (!failures.isEmpty()) {
        out.println("Assertion failures:");
        for (String f : failures) {
          out.println("  - " + f);
        }
      }
    }

    private static Map<String, Object> runToMap(ScenarioRunResult result, ReplayReport replay) {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("scenario", result.scenarioName());
      root.put("seed", result.seed());
      root.put("passed", replay != null ? replay.passed() : result.passed());
      List<Map<String, Object>> stepList = new ArrayList<>();
      for (StepOutcome s : result.steps()) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", s.step());
        m.put("action", s.action().name());
        m.put("transactionId", s.transactionId());
        m.put("state", s.state());
        m.put("pendingAction", s.pendingAction());
        if (s.reasonCode() != null) {
          m.put("reasonCode", s.reasonCode());
        }
        stepList.add(m);
      }
      root.put("steps", stepList);
      Map<String, String> bals = new LinkedHashMap<>();
      result.finalBalances().forEach((k, v) -> bals.put(k, v.toPlainString()));
      root.put("balances", bals);
      root.put("assertionFailures", result.assertionFailures());
      if (replay != null) {
        root.put("replayVerified", true);
        root.put("replayEquivalent", replay.verification().equivalent());
        root.put("diffs", replay.verification().diffs());
        root.put("normalizedEvents", replay.verification().normalizedRun1().size());
      } else {
        root.put("replayVerified", false);
      }
      return root;
    }

    private static StepOutcome toStepOutcome(
        StepSpec step, String idempKey, Map<String, Object> parsed) {
      return new StepOutcome(
          step.step(),
          step.action(),
          (String) parsed.get("transactionId"),
          idempKey,
          (String) parsed.get("state"),
          (String) parsed.get("pendingAction"),
          (String) parsed.get("reasonCode"),
          Boolean.TRUE.equals(parsed.get("needsManualReview")));
    }

    private static String extractFaultId(String responseBody, String fallbackId) {
      if (responseBody == null || responseBody.isBlank()) {
        return fallbackId;
      }
      try {
        Object parsed = ContractJson.parse(responseBody);
        if (parsed instanceof Map<?, ?> map) {
          Object faultId = map.get("faultId");
          if (faultId instanceof String s && !s.isBlank()) {
            return s;
          }
        }
      } catch (IllegalArgumentException ignored) {
        // Ignore malformed response body; caller fallback handles delete best-effort.
      }
      return fallbackId;
    }

    @SuppressWarnings("unchecked")
    private static void fetchTraceEvents(FinSimClient client, String txId, List<TraceEvent> out)
        throws IOException, InterruptedException {
      FinSimClient.Response traceRes = client.get("/api/v1/transfers/" + txId + "/trace");
      if (!traceRes.ok()) {
        return;
      }
      Map<String, Object> body = (Map<String, Object>) ContractJson.parse(traceRes.body());
      List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
      if (events == null) {
        return;
      }
      for (Map<String, Object> e : events) {
        out.add(
            new TraceEvent(
                txId,
                null,
                (String) e.get("kind"),
                (String) e.get("stage"),
                (String) e.get("target"),
                null,
                null,
                e.get("request"),
                e.get("response"),
                e.get("durationMs") instanceof BigDecimal bd ? bd.longValue() : null,
                (String) e.get("faultId"),
                (String) e.get("fromState"),
                (String) e.get("toState"),
                null));
      }
    }
  }
}
