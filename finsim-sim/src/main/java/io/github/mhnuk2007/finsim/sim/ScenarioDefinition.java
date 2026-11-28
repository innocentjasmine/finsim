package io.github.mhnuk2007.finsim.sim;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable declarative scenario model ({@code finsim/scenario/v1}). Monetary values are stored
 * exclusively as {@link BigDecimal}; floating-point types are never used.
 */
public record ScenarioDefinition(
    String version,
    String name,
    String description,
    String network,
    long seed,
    Instant startTime,
    List<ParticipantSpec> participants,
    List<FaultRuleSpec> faultRules,
    List<StepSpec> steps,
    AssertionsSpec assertions) {

  public static final String SUPPORTED_VERSION = "finsim/scenario/v1";

  public ScenarioDefinition(
      String version,
      String name,
      String description,
      String network,
      long seed,
      List<ParticipantSpec> participants,
      List<FaultRuleSpec> faultRules,
      List<StepSpec> steps,
      AssertionsSpec assertions) {
    this(
        version,
        name,
        description,
        network,
        seed,
        null,
        participants,
        faultRules,
        steps,
        assertions);
  }

  public ScenarioDefinition {
    if (!SUPPORTED_VERSION.equals(version)) {
      throw new ScenarioValidationException(
          "Unsupported scenario version '%s' (expected '%s')"
              .formatted(version, SUPPORTED_VERSION));
    }
    if (name == null || name.isBlank()) {
      throw new ScenarioValidationException("Scenario 'name' is required.");
    }
    if (network == null || network.isBlank()) {
      throw new ScenarioValidationException("Scenario 'network' is required.");
    }
    participants = participants != null ? List.copyOf(participants) : List.of();
    if (participants.isEmpty()) {
      throw new ScenarioValidationException("Scenario must declare at least one participant.");
    }
    faultRules = faultRules != null ? List.copyOf(faultRules) : List.of();
    steps = steps != null ? List.copyOf(steps) : List.of();
    if (steps.isEmpty()) {
      throw new ScenarioValidationException("Scenario must declare at least one step.");
    }
    assertions = assertions != null ? assertions : AssertionsSpec.empty();
  }

  /** Returns configured virtual clock start time or {@link Instant#EPOCH} by default. */
  public Instant effectiveStartTime() {
    return startTime != null ? startTime : Instant.EPOCH;
  }

  public record ParticipantSpec(String id, List<AccountSpec> accounts) {
    public ParticipantSpec {
      if (id == null || id.isBlank()) {
        throw new ScenarioValidationException("Participant 'id' is required.");
      }
      accounts = accounts != null ? List.copyOf(accounts) : List.of();
      if (accounts.isEmpty()) {
        throw new ScenarioValidationException(
            "Participant '%s' must declare at least one account.".formatted(id));
      }
    }
  }

  public record AccountSpec(
      String reference, BigDecimal initialBalance, String currency, boolean closed) {
    public AccountSpec {
      if (reference == null || reference.isBlank()) {
        throw new ScenarioValidationException("Account 'reference' is required.");
      }
      Objects.requireNonNull(initialBalance, "Account 'initialBalance' is required.");
      if (initialBalance.signum() < 0) {
        throw new ScenarioValidationException(
            "Account '%s' initialBalance must be non-negative.".formatted(reference));
      }
      if (currency == null || !currency.matches("[A-Z]{3}")) {
        throw new ScenarioValidationException(
            "Account '%s' currency must be a 3-letter uppercase ISO code.".formatted(reference));
      }
    }
  }

  public record FaultRuleSpec(
      String id,
      String target,
      String stage,
      FaultType type,
      FaultBehavior behavior,
      Integer delayMs,
      BigDecimal probability,
      Integer times,
      Long seed) {

    public FaultRuleSpec {
      if (id == null || id.isBlank()) {
        throw new ScenarioValidationException("Fault rule 'id' is required.");
      }
      if (target == null || target.isBlank()) {
        throw new ScenarioValidationException("Fault rule '%s' target is required.".formatted(id));
      }
      Objects.requireNonNull(type, "Fault rule '%s' type is required.".formatted(id));
      behavior = behavior != null ? behavior : FaultBehavior.BEFORE_PROCESSING;
      probability = probability != null ? probability : BigDecimal.ONE;
      if (probability.compareTo(BigDecimal.ZERO) < 0 || probability.compareTo(BigDecimal.ONE) > 0) {
        throw new ScenarioValidationException(
            "Fault rule '%s' probability must be between 0 and 1.".formatted(id));
      }
      if (type == FaultType.DELAY && (delayMs == null || delayMs < 0)) {
        throw new ScenarioValidationException(
            "Fault rule '%s' with type DELAY requires non-negative delayMs.".formatted(id));
      }
      if (behavior == FaultBehavior.RESPONSE_LOST_AFTER_PROCESSING && type != FaultType.TIMEOUT) {
        throw new ScenarioValidationException(
            "Fault rule '%s' behavior RESPONSE_LOST_AFTER_PROCESSING is only valid for TIMEOUT."
                .formatted(id));
      }
      if (times != null && times <= 0) {
        throw new ScenarioValidationException(
            "Fault rule '%s' times must be greater than 0.".formatted(id));
      }
    }

    /** Converts this DSL rule specification into an executable {@link FaultRule}. */
    public FaultRule toFaultRule(long scenarioSeed) {
      long effectiveSeed = seed != null ? seed : scenarioSeed;
      return new FaultRule(
          id,
          target,
          type,
          delayMs,
          probability.doubleValue(),
          times,
          effectiveSeed,
          stage,
          behavior);
    }
  }

  public enum StepAction {
    TRANSFER,
    RESOLVE,
    ADVANCE_TIME
  }

  public record AccountEndpoint(String institutionId, String accountReference) {
    public static AccountEndpoint parse(String raw, String fieldName) {
      if (raw == null || raw.isBlank()) {
        throw new ScenarioValidationException("Field '%s' is required.".formatted(fieldName));
      }
      int colon = raw.indexOf(':');
      if (colon <= 0 || colon >= raw.length() - 1) {
        throw new ScenarioValidationException(
            "Field '%s' must follow '<institutionId>:<accountReference>' format (got '%s')."
                .formatted(fieldName, raw));
      }
      String inst = raw.substring(0, colon).trim();
      String acc = raw.substring(colon + 1).trim();
      if (inst.isEmpty() || acc.isEmpty()) {
        throw new ScenarioValidationException(
            "Field '%s' must have non-empty institution and account reference."
                .formatted(fieldName));
      }
      return new AccountEndpoint(inst, acc);
    }

    public String qualified() {
      return institutionId + ":" + accountReference;
    }
  }

  public record StepSpec(
      int step,
      StepAction action,
      String transactionId,
      String idempotencyKey,
      Integer targetStep,
      AccountEndpoint from,
      AccountEndpoint to,
      BigDecimal amount,
      String currency,
      String reference,
      String expectedState,
      String expectedPendingAction,
      String expectedReasonCode,
      Duration advanceBy,
      Instant advanceTo) {

    public StepSpec(
        int step,
        StepAction action,
        String transactionId,
        String idempotencyKey,
        Integer targetStep,
        AccountEndpoint from,
        AccountEndpoint to,
        BigDecimal amount,
        String currency,
        String reference,
        String expectedState,
        String expectedPendingAction,
        String expectedReasonCode) {
      this(
          step,
          action,
          transactionId,
          idempotencyKey,
          targetStep,
          from,
          to,
          amount,
          currency,
          reference,
          expectedState,
          expectedPendingAction,
          expectedReasonCode,
          null,
          null);
    }

    public StepSpec {
      if (step < 1) {
        throw new ScenarioValidationException("Step number must be >= 1 (got %d).".formatted(step));
      }
      Objects.requireNonNull(action, "Step %d 'action' is required.".formatted(step));
      if (action == StepAction.TRANSFER) {
        if (from == null || to == null) {
          throw new ScenarioValidationException(
              "Step %d TRANSFER requires 'from' and 'to' accounts.".formatted(step));
        }
        if (amount == null || amount.signum() <= 0) {
          throw new ScenarioValidationException(
              "Step %d TRANSFER requires strictly positive 'amount'.".formatted(step));
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
          throw new ScenarioValidationException(
              "Step %d TRANSFER requires 3-letter uppercase 'currency'.".formatted(step));
        }
      }
      if (action == StepAction.ADVANCE_TIME) {
        if ((advanceBy == null) == (advanceTo == null)) {
          throw new ScenarioValidationException(
              "Step %d ADVANCE_TIME requires exactly one of 'advanceBy' or 'advanceTo'."
                  .formatted(step));
        }
        if (advanceBy != null && (advanceBy.isZero() || advanceBy.isNegative())) {
          throw new ScenarioValidationException(
              "Step %d ADVANCE_TIME 'advanceBy' must be strictly positive.".formatted(step));
        }
        if (from != null || to != null || amount != null || currency != null) {
          throw new ScenarioValidationException(
              "Step %d ADVANCE_TIME must not specify transfer fields (from/to/amount/currency)."
                  .formatted(step));
        }
      } else if (advanceBy != null || advanceTo != null) {
        throw new ScenarioValidationException(
            "Step %d %s must not specify 'advanceBy' or 'advanceTo'.".formatted(step, action));
      }
      if (idempotencyKey != null
          && (idempotencyKey.length() < 8 || idempotencyKey.length() > 128)) {
        throw new ScenarioValidationException(
            "Step %d idempotencyKey must be between 8 and 128 characters.".formatted(step));
      }
      if (targetStep != null && targetStep < 1) {
        throw new ScenarioValidationException("Step %d targetStep must be >= 1.".formatted(step));
      }
    }
  }

  public record AssertionsSpec(List<BalanceAssertion> balances, TraceAssertions trace) {
    public AssertionsSpec {
      balances = balances != null ? List.copyOf(balances) : List.of();
      trace = trace != null ? trace : new TraceAssertions(List.of());
    }

    public static AssertionsSpec empty() {
      return new AssertionsSpec(List.of(), new TraceAssertions(List.of()));
    }
  }

  public record BalanceAssertion(AccountEndpoint account, BigDecimal expectedBalance) {
    public BalanceAssertion {
      Objects.requireNonNull(account, "Balance assertion 'account' is required.");
      Objects.requireNonNull(expectedBalance, "Balance assertion 'expectedBalance' is required.");
    }
  }

  public record TraceAssertions(List<TraceExpectation> mustIncludeEvents) {
    public TraceAssertions {
      mustIncludeEvents = mustIncludeEvents != null ? List.copyOf(mustIncludeEvents) : List.of();
    }
  }

  public record TraceExpectation(
      String kind, String stage, String target, String faultId, String fromState, String toState) {
    public TraceExpectation {
      if (kind == null || kind.isBlank()) {
        throw new ScenarioValidationException("Trace assertion 'kind' is required.");
      }
    }
  }
}
