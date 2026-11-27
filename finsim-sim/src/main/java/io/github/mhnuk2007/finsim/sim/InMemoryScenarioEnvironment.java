package io.github.mhnuk2007.finsim.sim;

import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.AccountSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.ParticipantSpec;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Isolated, zero-Spring in-memory simulation environment backed by strict {@link BigDecimal}
 * synthetic participant ledgers.
 */
public final class InMemoryScenarioEnvironment extends BaseScenarioExecutor {

  private final Map<String, ParticipantLedger> participants = new LinkedHashMap<>();

  @Override
  protected void onInitialize(ScenarioDefinition scenario) {
    participants.clear();
    for (ParticipantSpec p : scenario.participants()) {
      participants.put(p.id(), new ParticipantLedger(p.id(), p.accounts()));
    }
  }

  @Override
  protected LegDispatchResult dispatchPaymentLeg(
      String targetInstitution,
      String idempotencyKey,
      Map<String, Object> payload,
      boolean sleepOnDelay,
      int delayMs) {
    ParticipantLedger ledger = participants.get(targetInstitution);
    if (ledger == null) {
      throw new ScenarioValidationException(
          "Unknown participant institution: " + targetInstitution);
    }
    return ledger.applyLeg(idempotencyKey, payload);
  }

  @Override
  protected String paymentEndpointForTrace(String targetInstitution) {
    return "/finsim/v1/payments";
  }

  @Override
  protected Optional<LegQueryResult> fetchLegStatus(
      String targetInstitution, String transactionId, String direction) {
    ParticipantLedger ledger = participants.get(targetInstitution);
    if (ledger == null) {
      throw new ScenarioValidationException(
          "Unknown participant institution: " + targetInstitution);
    }
    return ledger.queryStatus(transactionId, direction);
  }

  @Override
  protected String statusEndpointForTrace(
      String targetInstitution, String transactionId, String direction) {
    return "/finsim/v1/payments/" + transactionId + "?leg=" + direction;
  }

  @Override
  public Map<String, BigDecimal> balances() {
    Map<String, BigDecimal> snapshot = new TreeMap<>();
    for (ParticipantLedger ledger : participants.values()) {
      for (Map.Entry<String, AccountState> entry : ledger.accounts.entrySet()) {
        snapshot.put(ledger.institutionId + ":" + entry.getKey(), entry.getValue().balance);
      }
    }
    return snapshot;
  }

  private static final class AccountState {
    BigDecimal balance;
    final String currency;
    final boolean closed;

    AccountState(BigDecimal balance, String currency, boolean closed) {
      this.balance = balance;
      this.currency = currency;
      this.closed = closed;
    }
  }

  private record StoredIdempotentResponse(Map<String, Object> request, LegDispatchResult result) {}

  private static final class ParticipantLedger {
    final String institutionId;
    final Map<String, AccountState> accounts = new LinkedHashMap<>();
    final Set<String> supportedCurrencies = new HashSet<>();
    final Map<String, StoredIdempotentResponse> idempotency = new HashMap<>();
    final Map<String, Map<String, Object>> legs = new HashMap<>();
    final Map<String, BigDecimal> completedDebitAmounts = new HashMap<>();
    final Map<String, String> completedDebitAccounts = new HashMap<>();
    final Set<String> reversedDebits = new HashSet<>();
    long sequence = 0;

    ParticipantLedger(String institutionId, List<AccountSpec> accountSpecs) {
      this.institutionId = institutionId;
      for (AccountSpec spec : accountSpecs) {
        accounts.put(
            spec.reference(),
            new AccountState(spec.initialBalance(), spec.currency(), spec.closed()));
        supportedCurrencies.add(spec.currency());
      }
    }

    LegDispatchResult applyLeg(String key, Map<String, Object> request) {
      StoredIdempotentResponse existing = idempotency.get(key);
      if (existing != null) {
        if (!existing.request().equals(request)) {
          return LegDispatchResult.rejected("IDEMPOTENCY_CONFLICT", "Different request body");
        }
        return existing.result();
      }
      LegDispatchResult result = processLeg(request);
      idempotency.put(key, new StoredIdempotentResponse(Map.copyOf(request), result));
      return result;
    }

    @SuppressWarnings("unchecked")
    private LegDispatchResult processLeg(Map<String, Object> req) {
      String txId = (String) req.get("transactionId");
      String direction = (String) req.get("direction");
      String accountRef = (String) req.get("accountReference");
      Map<String, String> money = (Map<String, String>) req.get("money");
      BigDecimal amount = new BigDecimal(money.get("amount"));
      String currency = money.get("currency");
      String reversalOf = (String) req.get("reversalOf");

      String legKey = txId + "\u0000" + direction;
      if (legs.containsKey(legKey)) {
        return LegDispatchResult.rejected("DUPL", "Leg already exists");
      }
      if (!supportedCurrencies.contains(currency)) {
        return rememberRejection(legKey, txId, direction, "AM03", "Unsupported currency");
      }
      if (amount.signum() <= 0) {
        return rememberRejection(legKey, txId, direction, "AM02", "Amount must be positive");
      }
      AccountState account = accounts.get(accountRef);
      if (account == null) {
        return rememberRejection(legKey, txId, direction, "AC01", "Unknown account");
      }
      if (!account.currency.equals(currency)) {
        return rememberRejection(legKey, txId, direction, "AM03", "Account currency mismatch");
      }
      if (account.closed) {
        return rememberRejection(legKey, txId, direction, "AC04", "Account is closed");
      }

      if (reversalOf != null) {
        BigDecimal origAmt = completedDebitAmounts.get(reversalOf);
        String origAcc = completedDebitAccounts.get(reversalOf);
        if (origAmt == null
            || origAcc == null
            || !origAcc.equals(accountRef)
            || origAmt.compareTo(amount) != 0) {
          return rememberRejection(
              legKey, txId, direction, "MS03", "reversalOf does not match a completed debit");
        }
        if (reversedDebits.contains(reversalOf)) {
          return rememberRejection(legKey, txId, direction, "DUPL", "Debit already reversed");
        }
      }

      if ("DEBIT".equals(direction)) {
        if (account.balance.compareTo(amount) < 0) {
          return rememberRejection(legKey, txId, direction, "AM04", "Insufficient funds");
        }
        account.balance = account.balance.subtract(amount);
        completedDebitAmounts.put(txId, amount);
        completedDebitAccounts.put(txId, accountRef);
      } else {
        account.balance = account.balance.add(amount);
        if (reversalOf != null) {
          reversedDebits.add(reversalOf);
        }
      }

      String ref = "sim-" + institutionId + "-" + (++sequence);
      Map<String, Object> record = new LinkedHashMap<>();
      record.put("transactionId", txId);
      record.put("direction", direction);
      record.put("status", "COMPLETED");
      record.put("participantReference", ref);
      record.put("provenance", "SYNTHETIC");
      legs.put(legKey, record);
      return LegDispatchResult.completed(ref);
    }

    private LegDispatchResult rememberRejection(
        String legKey, String txId, String direction, String code, String msg) {
      String ref = "sim-" + institutionId + "-" + (++sequence);
      Map<String, Object> record = new LinkedHashMap<>();
      record.put("transactionId", txId);
      record.put("direction", direction);
      record.put("status", "REJECTED");
      record.put("reasonCode", code);
      record.put("participantReference", ref);
      record.put("provenance", "SYNTHETIC");
      legs.put(legKey, record);
      return LegDispatchResult.rejected(code, msg);
    }

    Optional<LegQueryResult> queryStatus(String txId, String direction) {
      Map<String, Object> rec = legs.get(txId + "\u0000" + direction);
      if (rec == null) {
        return Optional.empty();
      }
      return Optional.of(
          new LegQueryResult(
              (String) rec.get("status"),
              (String) rec.get("reasonCode"),
              (String) rec.get("participantReference"),
              rec));
    }
  }
}
