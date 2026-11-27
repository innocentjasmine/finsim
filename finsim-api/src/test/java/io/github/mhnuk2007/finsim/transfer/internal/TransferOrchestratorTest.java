package io.github.mhnuk2007.finsim.transfer.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.mhnuk2007.finsim.core.AccountRef;
import io.github.mhnuk2007.finsim.core.Emitter;
import io.github.mhnuk2007.finsim.core.Money;
import io.github.mhnuk2007.finsim.core.PendingAction;
import io.github.mhnuk2007.finsim.core.Provenance;
import io.github.mhnuk2007.finsim.core.StageEntry;
import io.github.mhnuk2007.finsim.core.StageOutcome;
import io.github.mhnuk2007.finsim.core.Transfer;
import io.github.mhnuk2007.finsim.core.TransferState;
import io.github.mhnuk2007.finsim.faults.FaultService;
import io.github.mhnuk2007.finsim.registry.Capability;
import io.github.mhnuk2007.finsim.registry.Institution;
import io.github.mhnuk2007.finsim.registry.InstitutionStatus;
import io.github.mhnuk2007.finsim.registry.InstitutionType;
import io.github.mhnuk2007.finsim.registry.Network;
import io.github.mhnuk2007.finsim.registry.Registry;
import io.github.mhnuk2007.finsim.sim.FaultBehavior;
import io.github.mhnuk2007.finsim.sim.FaultDecision;
import io.github.mhnuk2007.finsim.sim.FaultRule;
import io.github.mhnuk2007.finsim.sim.FaultType;
import io.github.mhnuk2007.finsim.transfer.TransferDtos.AccountRefDto;
import io.github.mhnuk2007.finsim.transfer.TransferDtos.CreateTransferRequest;
import io.github.mhnuk2007.finsim.transfer.TransferDtos.MoneyDto;
import io.github.mhnuk2007.finsim.transfer.internal.ParticipantClient.LegResponsePayload;
import io.github.mhnuk2007.finsim.transfer.internal.ParticipantClient.PaymentCallOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransferOrchestratorTest {

  private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

  private Registry registry;
  private ParticipantClient participantClient;
  private TransferPersistenceHelper persistenceHelper;
  private TransferOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    registry = mock(Registry.class);
    participantClient = mock(ParticipantClient.class);
    persistenceHelper = mock(TransferPersistenceHelper.class);
    orchestrator =
        new TransferOrchestrator(
            registry, participantClient, persistenceHelper, Clock.fixed(NOW, ZoneOffset.UTC));

    Institution bankA =
        new Institution(
            "bank-a",
            "Bank A",
            InstitutionType.BANK,
            "http://bank-a:9001",
            Set.of(Capability.ACCOUNT_DEBIT, Capability.ACCOUNT_CREDIT, Capability.STATUS_QUERY),
            InstitutionStatus.ACTIVE,
            "SYNTHETIC");
    Institution bankB =
        new Institution(
            "bank-b",
            "Bank B",
            InstitutionType.BANK,
            "http://bank-b:9002",
            Set.of(Capability.ACCOUNT_DEBIT, Capability.ACCOUNT_CREDIT, Capability.STATUS_QUERY),
            InstitutionStatus.ACTIVE,
            "SYNTHETIC");
    when(registry.institution("bank-a")).thenReturn(bankA);
    when(registry.institution("bank-b")).thenReturn(bankB);
  }

  @Test
  @DisplayName(
      "Issue #145: resolve() with PendingAction.REVERSE_DEBIT resolves completed reversal without querying or re-sending destination credit")
  void resolveWithPendingReverseDebitCompletesRejectionWhenReversalCompleted() {
    Transfer transfer =
        reconstituteUnknown(
            "tx-145-done",
            PendingAction.REVERSE_DEBIT,
            "[reasonCode=AC04] Reversal leg timed out: Read timed out");

    when(participantClient.queryLegStatus("http://bank-a:9001", "tx-145-done-rev", "CREDIT"))
        .thenReturn(
            Optional.of(
                new LegResponsePayload(
                    "tx-145-done-rev", "CREDIT", "COMPLETED", null, "rev-ok", "SYNTHETIC")));

    Transfer resolved = orchestrator.resolve(transfer);

    assertThat(resolved.getState()).isEqualTo(TransferState.REJECTED);
    assertThat(resolved.getReasonCode()).isEqualTo("AC04");
    assertThat(resolved.getEmittedBy()).isEqualTo(Emitter.DESTINATION);
    assertThat(resolved.getPendingAction()).isEqualTo(PendingAction.NONE);
    verify(participantClient, never()).queryLegStatus(eq("http://bank-b:9002"), any(), any());
    verify(participantClient, never()).applyLeg(eq("http://bank-b:9002"), any(), any());
    verify(persistenceHelper, times(1)).persistTransfer(transfer);
  }

  @Test
  @DisplayName(
      "Issue #145: resolve() with PendingAction.REVERSE_DEBIT flags manual review when source rejected reversal")
  void resolveWithPendingReverseDebitFlagsManualReviewWhenReversalRejected() {
    Transfer transfer =
        reconstituteUnknown(
            "tx-145-rej",
            PendingAction.REVERSE_DEBIT,
            "[reasonCode=AC04] Reversal leg timed out: Read timed out");

    when(participantClient.queryLegStatus("http://bank-a:9001", "tx-145-rej-rev", "CREDIT"))
        .thenReturn(
            Optional.of(
                new LegResponsePayload(
                    "tx-145-rej-rev", "CREDIT", "REJECTED", "BE04", null, "SYNTHETIC")));

    Transfer resolved = orchestrator.resolve(transfer);

    assertThat(resolved.getState()).isEqualTo(TransferState.UNKNOWN);
    assertThat(resolved.getPendingAction()).isEqualTo(PendingAction.REVERSE_DEBIT);
    assertThat(resolved.isNeedsManualReview()).isTrue();
    assertThat(resolved.getReasonCode()).isNull();
    verify(participantClient, never()).queryLegStatus(eq("http://bank-b:9002"), any(), any());
    verify(participantClient, never()).applyLeg(any(), any(), any());
    verify(persistenceHelper, times(1)).persistTransfer(transfer);
  }

  @Test
  @DisplayName(
      "Issue #145: resolve() with PendingAction.REVERSE_DEBIT re-sends reversal when source returns 404")
  void resolveWithPendingReverseDebitResendsReversalWhen404() {
    Transfer transfer =
        reconstituteUnknown(
            "tx-145-404",
            PendingAction.REVERSE_DEBIT,
            "[reasonCode=AC04] Reversal leg timed out: Read timed out");

    when(participantClient.queryLegStatus("http://bank-a:9001", "tx-145-404-rev", "CREDIT"))
        .thenReturn(Optional.empty());
    when(participantClient.applyLeg(
            eq("http://bank-a:9001"),
            eq("tx-145-404-rev"),
            argThat(req -> "tx-145-404".equals(req.reversalOf()))))
        .thenReturn(PaymentCallOutcome.completed("rev-ok-2"));

    Transfer resolved = orchestrator.resolve(transfer);

    assertThat(resolved.getState()).isEqualTo(TransferState.REJECTED);
    assertThat(resolved.getReasonCode()).isEqualTo("AC04");
    assertThat(resolved.getEmittedBy()).isEqualTo(Emitter.DESTINATION);
    verify(participantClient, never()).queryLegStatus(eq("http://bank-b:9002"), any(), any());
    verify(participantClient, never()).applyLeg(eq("http://bank-b:9002"), any(), any());
  }

  @Test
  @DisplayName(
      "Issue #146: resolve() keeps transfer in UNKNOWN when queryLegStatus throws SecurityException (SSRF)")
  void resolveKeepsTransferUnknownWhenQueryThrowsSecurityException() {
    Transfer transfer =
        reconstituteUnknown(
            "tx-146-ssrf", PendingAction.NONE, "Debit leg timed out at source institution");

    when(participantClient.queryLegStatus("http://bank-a:9001", "tx-146-ssrf", "DEBIT"))
        .thenThrow(new SecurityException("Private address blocked"));

    Transfer resolved = orchestrator.resolve(transfer);

    assertThat(resolved.getState()).isEqualTo(TransferState.UNKNOWN);
    assertThat(resolved.getReasonCode()).isNull();
    verify(persistenceHelper, times(1)).persistTransfer(transfer);
  }

  @Test
  @DisplayName(
      "Issue #26: RESPONSE_LOST_AFTER_PROCESSING forwards participant leg before timing out to UNKNOWN")
  void orchestrateResponseLostAfterProcessingForwardsLegThenTimesOut() {
    FaultService faultService = mock(FaultService.class);
    TransferTraceRecorder traceRecorder = mock(TransferTraceRecorder.class);
    TransferOrchestrator faultAwareOrchestrator =
        new TransferOrchestrator(
            registry,
            participantClient,
            persistenceHelper,
            traceRecorder,
            faultService,
            Clock.fixed(NOW, ZoneOffset.UTC));

    Institution bankA =
        new Institution(
            "bank-a",
            "Bank A",
            InstitutionType.BANK,
            "http://bank-a:9001",
            Set.of(Capability.ACCOUNT_DEBIT, Capability.ACCOUNT_CREDIT, Capability.STATUS_QUERY),
            InstitutionStatus.ACTIVE,
            "SYNTHETIC");
    Institution bankB =
        new Institution(
            "bank-b",
            "Bank B",
            InstitutionType.BANK,
            "http://bank-b:9002",
            Set.of(Capability.ACCOUNT_DEBIT, Capability.ACCOUNT_CREDIT, Capability.STATUS_QUERY),
            InstitutionStatus.ACTIVE,
            "SYNTHETIC");
    when(registry.requireRoutable("sandbox", "bank-a", Capability.ACCOUNT_DEBIT)).thenReturn(bankA);
    when(registry.requireRoutable("sandbox", "bank-b", Capability.ACCOUNT_CREDIT))
        .thenReturn(bankB);
    when(registry.network("sandbox"))
        .thenReturn(
            new Network(
                "sandbox",
                "FinSim Sandbox",
                Set.of("USD"),
                Set.of("bank-a", "bank-b"),
                null,
                "SYNTHETIC"));

    FaultRule creditLostRule =
        new FaultRule(
            "fault-credit-lost",
            "bank-b",
            FaultType.TIMEOUT,
            null,
            1.0,
            1,
            42L,
            "CREDIT",
            FaultBehavior.RESPONSE_LOST_AFTER_PROCESSING);
    when(faultService.evaluateOutboundCall("bank-a", "debit_source"))
        .thenReturn(FaultDecision.none());
    when(faultService.evaluateOutboundCall("bank-b", "credit_destination"))
        .thenReturn(FaultDecision.timeout(creditLostRule));

    when(participantClient.applyLeg(eq("http://bank-a:9001"), eq("tx-26-1-debit"), any()))
        .thenReturn(PaymentCallOutcome.completed("deb-ok"));
    when(participantClient.applyLeg(eq("http://bank-b:9002"), eq("tx-26-1-credit"), any()))
        .thenReturn(PaymentCallOutcome.completed("cred-ok-but-lost"));

    Transfer result =
        faultAwareOrchestrator.orchestrate(
            "tx-26-1",
            "sandbox",
            "idemp-26-1",
            new CreateTransferRequest(
                new AccountRefDto("bank-a", "ACC-1"),
                new AccountRefDto("bank-b", "ACC-2"),
                new MoneyDto("50.00", "USD"),
                "REF-26"));

    assertThat(result.getState()).isEqualTo(TransferState.UNKNOWN);
    assertThat(result.getPendingAction()).isEqualTo(PendingAction.RETRY_CREDIT);
    // Crucial: credit call WAS delivered to bank-b despite resulting in simulated TIMEOUT.
    verify(participantClient).applyLeg(eq("http://bank-b:9002"), eq("tx-26-1-credit"), any());
    verify(traceRecorder)
        .recordFaultInjected(
            eq("tx-26-1"), eq("credit_destination"), eq("bank-b"), eq("fault-credit-lost"), any());
  }

  private static Transfer reconstituteUnknown(
      String txId, PendingAction pendingAction, String details) {
    return Transfer.reconstitute(
        txId,
        "sandbox",
        "idemp-" + txId,
        TransferState.UNKNOWN,
        "resolve_reversal",
        new AccountRef("bank-a", "ACC-1"),
        new AccountRef("bank-b", "ACC-2"),
        Money.of("100.00", "USD"),
        "REF-" + txId,
        null,
        null,
        pendingAction,
        false,
        Provenance.SYNTHETIC,
        List.of(StageEntry.of("resolve_reversal", StageOutcome.UNKNOWN, NOW, details)),
        NOW,
        NOW,
        1L);
  }
}
