package io.github.mhnuk2007.finsim.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition;
import io.github.mhnuk2007.finsim.sim.ScenarioExecutor.StepOutcome;
import io.github.mhnuk2007.finsim.sim.ScenarioParser;
import io.github.mhnuk2007.finsim.sim.ScenarioRunner;
import io.github.mhnuk2007.finsim.sim.ScenarioRunner.ReplayReport;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ScenarioHttpAdapterTest {

  private static final String SCENARIO_YAML =
      """
      version: "finsim/scenario/v1"
      name: "http-lost-credit-recovery"
      network: "sandbox"
      seed: 424242
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
        - id: "fault-timeout-bank-b-credit"
          target: "bank-b"
          stage: "CREDIT"
          type: "TIMEOUT"
          behavior: "RESPONSE_LOST_AFTER_PROCESSING"
          times: 1
      steps:
        - step: 1
          action: "TRANSFER"
          transactionId: "tx-http-001"
          idempotencyKey: "idem-http-001"
          from: "bank-a:ACC-1"
          to: "bank-b:ACC-2"
          amount: "50.00"
          currency: "USD"
          expectedState: "UNKNOWN"
          expectedPendingAction: "RETRY_CREDIT"
        - step: 2
          action: "RESOLVE"
          transactionId: "tx-http-001"
          expectedState: "COMPLETED"
      assertions:
        balances:
          - account: "bank-a:ACC-1"
            expectedBalance: "450.00"
          - account: "bank-b:ACC-2"
            expectedBalance: "150.00"
        trace:
          mustIncludeEvents:
            - kind: "FAULT_INJECTED"
              faultId: "fault-timeout-bank-b-credit"
            - kind: "STATE_CHANGE"
              toState: "UNKNOWN"
            - kind: "STATE_CHANGE"
              toState: "COMPLETED"
      """;

  @Test
  void replaysEquivalentlyAcrossIsolatedHttpParticipantsOnDifferentEphemeralPorts()
      throws Exception {
    ScenarioDefinition scenario = new ScenarioParser().parseYaml(SCENARIO_YAML);
    ScenarioRunner runner =
        new ScenarioRunner(
            () -> {
              try {
                StubBank bankA = new StubBank("bank-a", "ACC-1", new BigDecimal("500.00"));
                StubBank bankB = new StubBank("bank-b", "ACC-2", new BigDecimal("100.00"));
                ScenarioHttpAdapter delegate =
                    ScenarioHttpAdapter.forParticipants(
                        Map.of("bank-a", bankA.baseUrl(), "bank-b", bankB.baseUrl()),
                        RestClient.builder());
                return new AutoClosingExecutor(delegate, bankA, bankB);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });

    ReplayReport report = runner.runWithReplayVerification(scenario);

    assertThat(report.passed()).isTrue();
    assertThat(report.verification().equivalent()).isTrue();
    assertThat(report.initialRun().finalBalances().get("bank-a:ACC-1"))
        .isEqualByComparingTo("450.00");
    assertThat(report.initialRun().finalBalances().get("bank-b:ACC-2"))
        .isEqualByComparingTo("150.00");
  }

  private static final class AutoClosingExecutor
      implements io.github.mhnuk2007.finsim.sim.ScenarioExecutor {
    private final ScenarioHttpAdapter delegate;
    private final StubBank bankA;
    private final StubBank bankB;

    AutoClosingExecutor(ScenarioHttpAdapter delegate, StubBank bankA, StubBank bankB) {
      this.delegate = delegate;
      this.bankA = bankA;
      this.bankB = bankB;
    }

    @Override
    public void initialize(ScenarioDefinition scenario) {
      delegate.initialize(scenario);
    }

    @Override
    public StepOutcome executeTransfer(
        ScenarioDefinition.StepSpec step, String transactionId, String idempotencyKey) {
      return delegate.executeTransfer(step, transactionId, idempotencyKey);
    }

    @Override
    public StepOutcome executeResolve(ScenarioDefinition.StepSpec step, String transactionId) {
      StepOutcome outcome = delegate.executeResolve(step, transactionId);
      // Verify real participant server balances independently of adapter tracking.
      assertThat(bankA.balance("ACC-1")).isEqualByComparingTo("450.00");
      assertThat(bankB.balance("ACC-2")).isEqualByComparingTo("150.00");
      bankA.close();
      bankB.close();
      return outcome;
    }

    @Override
    public Map<String, BigDecimal> balances() {
      return delegate.balances();
    }

    @Override
    public java.util.List<io.github.mhnuk2007.finsim.sim.TraceEvent> trace() {
      return delegate.trace();
    }
  }

  private static final class StubBank {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String id;
    private final Map<String, BigDecimal> accounts = new HashMap<>();
    private final Map<String, Map<String, Object>> legs = new HashMap<>();
    private final HttpServer server;
    private long seq;

    StubBank(String id, String account, BigDecimal initial) throws IOException {
      this.id = id;
      accounts.put(account, initial);
      this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      this.server.createContext("/finsim/v1/payments", this::handlePayments);
      this.server.start();
    }

    String baseUrl() {
      return "http://localhost:" + server.getAddress().getPort();
    }

    BigDecimal balance(String account) {
      return accounts.get(account);
    }

    void close() {
      server.stop(0);
    }

    @SuppressWarnings("unchecked")
    private void handlePayments(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getPath();
      if ("POST".equals(exchange.getRequestMethod()) && "/finsim/v1/payments".equals(path)) {
        Map<String, Object> body =
            mapper.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
        String txId = (String) body.get("transactionId");
        String dir = (String) body.get("direction");
        String acc = (String) body.get("accountReference");
        BigDecimal amt =
            new BigDecimal((String) ((Map<String, Object>) body.get("money")).get("amount"));
        if ("DEBIT".equals(dir)) {
          accounts.compute(acc, (k, v) -> v.subtract(amt));
        } else {
          accounts.compute(acc, (k, v) -> v.add(amt));
        }
        Map<String, Object> leg = new LinkedHashMap<>();
        leg.put("transactionId", txId);
        leg.put("direction", dir);
        leg.put("status", "COMPLETED");
        leg.put("participantReference", id + "-" + (++seq));
        leg.put("provenance", "SYNTHETIC");
        legs.put(txId + ":" + dir, leg);
        writeJson(exchange, 201, leg);
        return;
      }
      if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/finsim/v1/payments/")) {
        String txId = path.substring("/finsim/v1/payments/".length());
        String query = exchange.getRequestURI().getQuery();
        String dir = query != null && query.startsWith("leg=") ? query.substring(4) : "";
        Map<String, Object> leg = legs.get(txId + ":" + dir);
        if (leg == null) {
          writeJson(exchange, 404, Map.of("code", "LEG_NOT_FOUND", "message", "missing"));
          return;
        }
        writeJson(exchange, 200, leg);
        return;
      }
      writeJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "missing"));
    }

    private void writeJson(HttpExchange exchange, int status, Map<String, Object> body)
        throws IOException {
      byte[] bytes = mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }
  }
}
