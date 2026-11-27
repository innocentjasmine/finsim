package io.github.mhnuk2007.finsim.sim;

import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.FaultRuleSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.StepAction;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.StepSpec;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared {@link ScenarioExecutor} template implementing two-leg payment orchestration, stage-aware
 * fault evaluation, {@link FaultBehavior#RESPONSE_LOST_AFTER_PROCESSING}, ADR 0001 {@code /resolve}
 * state transitions, and canonical trace recording.
 */
public abstract class BaseScenarioExecutor implements ScenarioExecutor {

  public record LegDispatchResult(
      String status, String participantReference, String reasonCode, String errorMessage) {

    public static LegDispatchResult completed(String participantReference) {
      return new LegDispatchResult("COMPLETED", participantReference, null, null);
    }

    public static LegDispatchResult rejected(String reasonCode, String errorMessage) {
      return new LegDispatchResult("REJECTED", null, reasonCode, errorMessage);
    }

    public static LegDispatchResult timedOut(String errorMessage) {
      return new LegDispatchResult("TIMED_OUT", null, null, errorMessage);
    }

    public Map<String, Object> toTraceMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("status", status);
      if (participantReference != null) {
        map.put("participantReference", participantReference);
      }
      if (reasonCode != null) {
        map.put("reasonCode", reasonCode);
      }
      if (errorMessage != null) {
        map.put("errorMessage", errorMessage);
      }
      return map;
    }
  }

  public record LegQueryResult(
      String status, String reasonCode, String participantReference, Map<String, Object> rawBody) {}

  private final List<FaultRule> faultRules = new ArrayList<>();
  private final FaultEvaluator faultEvaluator = new FaultEvaluator();
  private final Map<String, TransferRecord> transfersById = new HashMap<>();
  private final Map<String, TransferRecord> transfersByKey = new HashMap<>();
  private final List<TraceEvent> traceEvents = new ArrayList<>();
  private String networkId;

  @Override
  public final void initialize(ScenarioDefinition scenario) {
    faultRules.clear();
    faultEvaluator.resetAll();
    transfersById.clear();
    transfersByKey.clear();
    traceEvents.clear();
    this.networkId = scenario.network();

    for (FaultRuleSpec ruleSpec : scenario.faultRules()) {
      faultRules.add(ruleSpec.toFaultRule(scenario.seed()));
    }
    onInitialize(scenario);
  }

  protected abstract void onInitialize(ScenarioDefinition scenario);

  protected abstract LegDispatchResult dispatchPaymentLeg(
      String targetInstitution,
      String idempotencyKey,
      Map<String, Object> payload,
      boolean sleepOnDelay,
      int delayMs);

  protected abstract String paymentEndpointForTrace(String targetInstitution);

  protected abstract Optional<LegQueryResult> fetchLegStatus(
      String targetInstitution, String transactionId, String direction);

  protected abstract String statusEndpointForTrace(
      String targetInstitution, String transactionId, String direction);

  protected void onDebitConfirmed(
      String institutionId, String accountReference, BigDecimal amount) {}

  protected void onCreditConfirmed(
      String institutionId, String accountReference, BigDecimal amount) {}

  protected void onReversalConfirmed(
      String institutionId, String accountReference, BigDecimal amount) {}

  @Override
  public final StepOutcome executeTransfer(
      StepSpec step, String transactionId, String idempotencyKey) {
    String reference = step.reference() != null ? step.reference() : "SCENARIO-" + step.step();
    TransferRecord existing = transfersByKey.get(idempotencyKey);
    if (existing != null) {
      if (!existing.matchesRequest(
          networkId,
          step.from().qualified(),
          step.to().qualified(),
          step.amount(),
          step.currency(),
          reference)) {
        return new StepOutcome(
            step.step(),
            StepAction.TRANSFER,
            existing.transactionId,
            idempotencyKey,
            "IDEMPOTENCY_CONFLICT",
            existing.pendingAction,
            "IDEMPOTENCY_CONFLICT",
            existing.needsManualReview);
      }
      if ("UNKNOWN".equals(existing.state)) {
        return new StepOutcome(
            step.step(),
            StepAction.TRANSFER,
            existing.transactionId,
            idempotencyKey,
            "RECONCILIATION_REQUIRED",
            existing.pendingAction,
            "RECONCILIATION_REQUIRED",
            existing.needsManualReview);
      }
      return existing.toStepOutcome(step.step(), StepAction.TRANSFER);
    }

    TransferRecord transfer =
        new TransferRecord(
            transactionId,
            networkId,
            idempotencyKey,
            step.from().institutionId(),
            step.from().accountReference(),
            step.to().institutionId(),
            step.to().accountReference(),
            step.amount(),
            step.currency(),
            reference);
    transfersById.put(transactionId, transfer);
    transfersByKey.put(idempotencyKey, transfer);

    recordStageAndTransition(transactionId, "created", null, "CREATED");
    transfer.state = "ACCEPTED";
    recordStageAndTransition(transactionId, "accepted", "CREATED", "ACCEPTED");
    transfer.state = "IN_PROGRESS";
    recordStageAndTransition(transactionId, "debit_source", "ACCEPTED", "IN_PROGRESS");

    String debitKey = transactionId + "-debit";
    Map<String, Object> debitReq =
        legRequest(
            transactionId,
            "DEBIT",
            transfer.fromAccount,
            transfer.amount,
            transfer.currency,
            reference,
            null);
    LegDispatchResult debitOutcome =
        sendLeg(transactionId, "debit_source", transfer.fromInst, debitKey, debitReq);

    if ("REJECTED".equals(debitOutcome.status())) {
      transfer.state = "REJECTED";
      transfer.reasonCode = debitOutcome.reasonCode();
      transfer.pendingAction = "NONE";
      traceEvents.add(
          TraceEvent.stateChange(
              transactionId, "debit_source", "IN_PROGRESS", "REJECTED", Instant.now()));
      return transfer.toStepOutcome(step.step(), StepAction.TRANSFER);
    }
    if ("TIMED_OUT".equals(debitOutcome.status())) {
      transfer.state = "UNKNOWN";
      transfer.pendingAction = "NONE";
      traceEvents.add(
          TraceEvent.stateChange(
              transactionId, "debit_source", "IN_PROGRESS", "UNKNOWN", Instant.now()));
      return transfer.toStepOutcome(step.step(), StepAction.TRANSFER);
    }

    confirmDebitOnce(transfer);

    traceEvents.add(TraceEvent.stage(transactionId, "credit_destination", Instant.now()));
    String creditKey = transactionId + "-credit";
    Map<String, Object> creditReq =
        legRequest(
            transactionId,
            "CREDIT",
            transfer.toAccount,
            transfer.amount,
            transfer.currency,
            reference,
            null);
    LegDispatchResult creditOutcome =
        sendLeg(transactionId, "credit_destination", transfer.toInst, creditKey, creditReq);

    switch (creditOutcome.status()) {
      case "COMPLETED" -> {
        confirmCreditOnce(transfer);
        transfer.state = "COMPLETED";
        transfer.pendingAction = "NONE";
        traceEvents.add(
            TraceEvent.stateChange(
                transactionId, "credit_destination", "IN_PROGRESS", "COMPLETED", Instant.now()));
      }
      case "TIMED_OUT" -> {
        transfer.state = "UNKNOWN";
        transfer.pendingAction = "RETRY_CREDIT";
        traceEvents.add(
            TraceEvent.stateChange(
                transactionId, "credit_destination", "IN_PROGRESS", "UNKNOWN", Instant.now()));
      }
      case "REJECTED" -> {
        transfer.state = "UNKNOWN";
        transfer.pendingAction = "REVERSE_DEBIT";
        transfer.pendingReversalReason = creditOutcome.reasonCode();
        traceEvents.add(
            TraceEvent.stateChange(
                transactionId, "credit_destination", "IN_PROGRESS", "UNKNOWN", Instant.now()));
        executeReversal(transfer, creditOutcome.reasonCode());
      }
      default ->
          throw new IllegalStateException("Unexpected leg status: " + creditOutcome.status());
    }
    return transfer.toStepOutcome(step.step(), StepAction.TRANSFER);
  }

  @Override
  public final StepOutcome executeResolve(StepSpec step, String transactionId) {
    TransferRecord transfer = transfersById.get(transactionId);
    if (transfer == null) {
      throw new ScenarioValidationException(
          "Step %d RESOLVE references unknown transactionId '%s'."
              .formatted(step.step(), transactionId));
    }
    if ("COMPLETED".equals(transfer.state) || "REJECTED".equals(transfer.state)) {
      return transfer.toStepOutcome(step.step(), StepAction.RESOLVE);
    }
    if (!"UNKNOWN".equals(transfer.state)) {
      throw new IllegalStateException(
          "Transfer '%s' is in state '%s' and cannot be resolved."
              .formatted(transactionId, transfer.state));
    }
    if ("REVERSE_DEBIT".equals(transfer.pendingAction)) {
      return resolvePendingReversal(step.step(), transfer);
    }

    Optional<LegQueryResult> debitStatus =
        queryLeg(transactionId, "query_debit", transfer.fromInst, transactionId, "DEBIT");
    if (debitStatus.isEmpty()) {
      transfer.state = "REJECTED";
      transfer.reasonCode = "FINSIM_TIMEOUT";
      transfer.pendingAction = "NONE";
      recordStageAndTransition(transactionId, "resolve", "UNKNOWN", "REJECTED");
      return transfer.toStepOutcome(step.step(), StepAction.RESOLVE);
    }
    if ("REJECTED".equalsIgnoreCase(debitStatus.get().status())) {
      transfer.state = "REJECTED";
      transfer.reasonCode = nonBlankOr(debitStatus.get().reasonCode(), "REJECTED");
      transfer.pendingAction = "NONE";
      recordStageAndTransition(transactionId, "resolve", "UNKNOWN", "REJECTED");
      return transfer.toStepOutcome(step.step(), StepAction.RESOLVE);
    }
    if (!"COMPLETED".equalsIgnoreCase(debitStatus.get().status())) {
      return transfer.toStepOutcome(step.step(), StepAction.RESOLVE);
    }
    confirmDebitOnce(transfer);

    Optional<LegQueryResult> creditStatus =
        queryLeg(transactionId, "query_credit", transfer.toInst, transactionId, "CREDIT");
    if (creditStatus.isPresent()) {
      String status = creditStatus.get().status();
      if ("COMPLETED".equalsIgnoreCase(status)) {
        confirmCreditOnce(transfer);
        transfer.state = "COMPLETED";
        transfer.pendingAction = "NONE";
        recordStageAndTransition(transactionId, "resolve", "UNKNOWN", "COMPLETED");
        return transfer.toStepOutcome(step.step(), StepAction.RESOLVE);
      }
      if ("ACCEPTED".equalsIgnoreCase(status)) {
        transfer.pendingAction = "RETRY_CREDIT";
        return transfer.toStepOutcome(step.step(), StepAction.RESOLVE);
      }
      if ("REJECTED".equalsIgnoreCase(status)) {
        executeReversal(transfer, nonBlankOr(creditStatus.get().reasonCode(), "REJECTED"));
        return transfer.toStepOutcome(step.step(), StepAction.RESOLVE);
      }
      return transfer.toStepOutcome(step.step(), StepAction.RESOLVE);
    }

    traceEvents.add(TraceEvent.stage(transactionId, "resolve_credit", Instant.now()));
    Map<String, Object> creditReq =
        legRequest(
            transactionId,
            "CREDIT",
            transfer.toAccount,
            transfer.amount,
            transfer.currency,
            transfer.reference,
            null);
    LegDispatchResult resend =
        sendLeg(
            transactionId, "resolve_credit", transfer.toInst, transactionId + "-credit", creditReq);
    switch (resend.status()) {
      case "COMPLETED" -> {
        confirmCreditOnce(transfer);
        transfer.state = "COMPLETED";
        transfer.pendingAction = "NONE";
        traceEvents.add(
            TraceEvent.stateChange(
                transactionId, "resolve_credit", "UNKNOWN", "COMPLETED", Instant.now()));
      }
      case "TIMED_OUT" -> transfer.pendingAction = "RETRY_CREDIT";
      case "REJECTED" -> executeReversal(transfer, resend.reasonCode());
      default -> throw new IllegalStateException("Unexpected leg status: " + resend.status());
    }
    return transfer.toStepOutcome(step.step(), StepAction.RESOLVE);
  }

  private StepOutcome resolvePendingReversal(int stepIndex, TransferRecord transfer) {
    String revId = transfer.transactionId + "-rev";
    String destReason = nonBlankOr(transfer.pendingReversalReason, "REJECTED");
    Optional<LegQueryResult> revStatus =
        queryLeg(transfer.transactionId, "query_reversal", transfer.fromInst, revId, "CREDIT");
    if (revStatus.isPresent()) {
      String status = revStatus.get().status();
      if ("COMPLETED".equalsIgnoreCase(status)) {
        confirmReversalOnce(transfer);
        transfer.state = "REJECTED";
        transfer.reasonCode = destReason;
        transfer.pendingAction = "NONE";
        transfer.needsManualReview = false;
        recordStageAndTransition(transfer.transactionId, "resolve_reversal", "UNKNOWN", "REJECTED");
        return transfer.toStepOutcome(stepIndex, StepAction.RESOLVE);
      }
      if ("REJECTED".equalsIgnoreCase(status)) {
        transfer.pendingAction = "REVERSE_DEBIT";
        transfer.needsManualReview = true;
        return transfer.toStepOutcome(stepIndex, StepAction.RESOLVE);
      }
      return transfer.toStepOutcome(stepIndex, StepAction.RESOLVE);
    }
    executeReversal(transfer, destReason);
    return transfer.toStepOutcome(stepIndex, StepAction.RESOLVE);
  }

  private void executeReversal(TransferRecord transfer, String rejectionReason) {
    traceEvents.add(TraceEvent.stage(transfer.transactionId, "resolve_reversal", Instant.now()));
    String destReason = nonBlankOr(rejectionReason, "REJECTED");
    String revId = transfer.transactionId + "-rev";
    Map<String, Object> revReq =
        legRequest(
            revId,
            "CREDIT",
            transfer.fromAccount,
            transfer.amount,
            transfer.currency,
            "Reversal of " + transfer.transactionId,
            transfer.transactionId);
    LegDispatchResult revOutcome =
        sendLeg(transfer.transactionId, "resolve_reversal", transfer.fromInst, revId, revReq);
    switch (revOutcome.status()) {
      case "COMPLETED" -> {
        confirmReversalOnce(transfer);
        transfer.state = "REJECTED";
        transfer.reasonCode = destReason;
        transfer.pendingAction = "NONE";
        transfer.needsManualReview = false;
        traceEvents.add(
            TraceEvent.stateChange(
                transfer.transactionId, "resolve_reversal", "UNKNOWN", "REJECTED", Instant.now()));
      }
      case "TIMED_OUT" -> {
        transfer.pendingAction = "REVERSE_DEBIT";
        transfer.pendingReversalReason = destReason;
        transfer.needsManualReview = false;
      }
      case "REJECTED" -> {
        transfer.pendingAction = "REVERSE_DEBIT";
        transfer.pendingReversalReason = destReason;
        transfer.needsManualReview = true;
      }
      default ->
          throw new IllegalStateException("Unexpected reversal outcome: " + revOutcome.status());
    }
  }

  private LegDispatchResult sendLeg(
      String transferId,
      String stage,
      String targetInst,
      String idempotencyKey,
      Map<String, Object> payload) {
    long startNs = System.nanoTime();
    Instant callAt = Instant.now();
    String endpoint = paymentEndpointForTrace(targetInst);

    FaultEvaluator.EvaluationResult eval = faultEvaluator.evaluate(faultRules, targetInst, stage);
    if (eval.updatedRule().isPresent()) {
      FaultRule updated = eval.updatedRule().get();
      for (int i = 0; i < faultRules.size(); i++) {
        if (faultRules.get(i).faultId().equals(updated.faultId())) {
          faultRules.set(i, updated);
          break;
        }
      }
    }

    int delayMs = 0;
    boolean sleepOnDelay = false;
    if (eval.decision().triggered()) {
      FaultDecision decision = eval.decision();
      String faultId =
          decision.matchedRule() != null
              ? decision.matchedRule().faultId()
              : decision.faultType().name();
      traceEvents.add(
          TraceEvent.faultInjected(transferId, stage, targetInst, faultId, Instant.now()));

      if (decision.faultType() == FaultType.TIMEOUT) {
        if (decision.behavior() == FaultBehavior.RESPONSE_LOST_AFTER_PROCESSING) {
          dispatchPaymentLeg(targetInst, idempotencyKey, payload, false, 0);
        }
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        LegDispatchResult timedOut =
            LegDispatchResult.timedOut(
                "Simulated " + decision.behavior() + " timeout on target " + targetInst);
        traceEvents.add(
            TraceEvent.outboundCall(
                transferId,
                stage,
                targetInst,
                "POST",
                endpoint,
                payload,
                timedOut.toTraceMap(),
                durationMs,
                callAt));
        return timedOut;
      }

      if (decision.faultType() == FaultType.DELAY) {
        delayMs = decision.delayMs();
        sleepOnDelay = delayMs > 0;
      } else if (decision.faultType() == FaultType.DUPLICATE_DELIVERY) {
        LegDispatchResult first = dispatchPaymentLeg(targetInst, idempotencyKey, payload, false, 0);
        traceEvents.add(
            TraceEvent.outboundCall(
                transferId,
                stage,
                targetInst,
                "POST",
                endpoint,
                payload,
                first.toTraceMap(),
                0L,
                callAt));
      }
    }

    LegDispatchResult outcome =
        dispatchPaymentLeg(targetInst, idempotencyKey, payload, sleepOnDelay, delayMs);
    long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
    traceEvents.add(
        TraceEvent.outboundCall(
            transferId,
            stage,
            targetInst,
            "POST",
            endpoint,
            payload,
            outcome.toTraceMap(),
            durationMs,
            callAt));
    return outcome;
  }

  private Optional<LegQueryResult> queryLeg(
      String transferId, String stage, String targetInst, String legTxId, String direction) {
    traceEvents.add(TraceEvent.stage(transferId, stage, Instant.now()));
    String endpoint = statusEndpointForTrace(targetInst, legTxId, direction);
    long startNs = System.nanoTime();
    Instant callAt = Instant.now();
    Optional<LegQueryResult> res = fetchLegStatus(targetInst, legTxId, direction);
    long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
    traceEvents.add(
        TraceEvent.outboundCall(
            transferId,
            stage,
            targetInst,
            "GET",
            endpoint,
            Map.of("transactionId", legTxId, "leg", direction),
            res.map(LegQueryResult::rawBody).orElse(null),
            durationMs,
            callAt));
    return res;
  }

  private void confirmDebitOnce(TransferRecord t) {
    if (!t.debitApplied) {
      onDebitConfirmed(t.fromInst, t.fromAccount, t.amount);
      t.debitApplied = true;
    }
  }

  private void confirmCreditOnce(TransferRecord t) {
    if (!t.creditApplied) {
      onCreditConfirmed(t.toInst, t.toAccount, t.amount);
      t.creditApplied = true;
    }
  }

  private void confirmReversalOnce(TransferRecord t) {
    if (!t.reversalApplied && t.debitApplied) {
      onReversalConfirmed(t.fromInst, t.fromAccount, t.amount);
      t.reversalApplied = true;
    }
  }

  private void recordStageAndTransition(
      String transferId, String stage, String fromState, String toState) {
    Instant now = Instant.now();
    traceEvents.add(TraceEvent.stage(transferId, stage, now));
    traceEvents.add(TraceEvent.stateChange(transferId, stage, fromState, toState, now));
  }

  @Override
  public final List<TraceEvent> trace() {
    return List.copyOf(traceEvents);
  }

  private static Map<String, Object> legRequest(
      String transactionId,
      String direction,
      String accountReference,
      BigDecimal amount,
      String currency,
      String reference,
      String reversalOf) {
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("transactionId", transactionId);
    req.put("direction", direction);
    req.put("accountReference", accountReference);
    req.put("money", Map.of("amount", amount.toPlainString(), "currency", currency));
    req.put("reference", reference);
    if (reversalOf != null) {
      req.put("reversalOf", reversalOf);
    }
    req.put("provenance", "SYNTHETIC");
    return req;
  }

  private static String nonBlankOr(String candidate, String fallback) {
    return candidate != null && !candidate.isBlank() ? candidate : fallback;
  }

  private static final class TransferRecord {
    final String transactionId;
    final String networkId;
    final String idempotencyKey;
    final String fromInst;
    final String fromAccount;
    final String toInst;
    final String toAccount;
    final BigDecimal amount;
    final String currency;
    final String reference;
    String state = "CREATED";
    String pendingAction = "NONE";
    String reasonCode;
    String pendingReversalReason;
    boolean needsManualReview;
    boolean debitApplied;
    boolean creditApplied;
    boolean reversalApplied;

    TransferRecord(
        String transactionId,
        String networkId,
        String idempotencyKey,
        String fromInst,
        String fromAccount,
        String toInst,
        String toAccount,
        BigDecimal amount,
        String currency,
        String reference) {
      this.transactionId = transactionId;
      this.networkId = networkId;
      this.idempotencyKey = idempotencyKey;
      this.fromInst = fromInst;
      this.fromAccount = fromAccount;
      this.toInst = toInst;
      this.toAccount = toAccount;
      this.amount = amount;
      this.currency = currency;
      this.reference = reference;
    }

    boolean matchesRequest(
        String net, String from, String to, BigDecimal amt, String curr, String ref) {
      return networkId.equals(net)
          && (fromInst + ":" + fromAccount).equals(from)
          && (toInst + ":" + toAccount).equals(to)
          && amount.compareTo(amt) == 0
          && currency.equals(curr)
          && reference.equals(ref);
    }

    StepOutcome toStepOutcome(int stepIndex, StepAction action) {
      return new StepOutcome(
          stepIndex,
          action,
          transactionId,
          idempotencyKey,
          state,
          pendingAction,
          reasonCode,
          needsManualReview);
    }
  }
}
