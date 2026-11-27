package io.github.mhnuk2007.finsim.scenario;

import io.github.mhnuk2007.finsim.sim.BaseScenarioExecutor;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.AccountSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.ParticipantSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioValidationException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP execution adapter for {@code finsim-sim}'s {@link
 * io.github.mhnuk2007.finsim.sim.ScenarioRunner}, executing scenario steps over real {@code
 * finsim/v1} HTTP calls against participant servers.
 */
public final class ScenarioHttpAdapter extends BaseScenarioExecutor {

  public record LegResponsePayload(
      String transactionId,
      String direction,
      String status,
      String reasonCode,
      String participantReference,
      String provenance) {}

  public record ErrorPayload(String code, String message) {}

  private final Map<String, String> participantBaseUrls;
  private final RestClient restClient;
  private final Map<String, BigDecimal> balances = new TreeMap<>();

  public ScenarioHttpAdapter(Map<String, String> participantBaseUrls, RestClient restClient) {
    this.participantBaseUrls =
        Map.copyOf(Objects.requireNonNull(participantBaseUrls, "participantBaseUrls required"));
    this.restClient = Objects.requireNonNull(restClient, "restClient required");
  }

  public static ScenarioHttpAdapter forParticipants(
      Map<String, String> participantBaseUrls, RestClient.Builder builder) {
    return new ScenarioHttpAdapter(participantBaseUrls, builder.build());
  }

  @Override
  protected void onInitialize(ScenarioDefinition scenario) {
    balances.clear();
    for (ParticipantSpec participant : scenario.participants()) {
      if (!participantBaseUrls.containsKey(participant.id())) {
        throw new ScenarioValidationException(
            "No HTTP baseUrl mapped for participant '%s'.".formatted(participant.id()));
      }
      for (AccountSpec acc : participant.accounts()) {
        balances.put(participant.id() + ":" + acc.reference(), acc.initialBalance());
      }
    }
  }

  @Override
  protected LegDispatchResult dispatchPaymentLeg(
      String targetInstitution,
      String idempotencyKey,
      Map<String, Object> payload,
      boolean sleepOnDelay,
      int delayMs) {
    if (sleepOnDelay && delayMs > 0) {
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    String url = paymentEndpointForTrace(targetInstitution);
    try {
      LegResponsePayload res =
          restClient
              .post()
              .uri(url)
              .header("Idempotency-Key", idempotencyKey)
              .contentType(MediaType.APPLICATION_JSON)
              .body(payload)
              .retrieve()
              .body(LegResponsePayload.class);
      if (res == null || res.status() == null || res.status().isBlank()) {
        return LegDispatchResult.timedOut("Empty participant response");
      }
      String upper = res.status().toUpperCase();
      if ("COMPLETED".equals(upper)) {
        return LegDispatchResult.completed(res.participantReference());
      }
      if ("REJECTED".equals(upper)) {
        return LegDispatchResult.rejected(
            res.reasonCode() != null && !res.reasonCode().isBlank() ? res.reasonCode() : "REJECTED",
            "Participant returned REJECTED");
      }
      return LegDispatchResult.timedOut("Participant returned status: " + res.status());
    } catch (ResourceAccessException ex) {
      return LegDispatchResult.timedOut(ex.getMessage());
    } catch (RestClientResponseException ex) {
      int code = ex.getStatusCode().value();
      if (code == 422 || code == 400 || code == 409) {
        ErrorPayload err = ex.getResponseBodyAs(ErrorPayload.class);
        return LegDispatchResult.rejected(
            err != null && err.code() != null ? err.code() : "REJECTED",
            err != null ? err.message() : ex.getMessage());
      }
      return LegDispatchResult.timedOut("HTTP " + code + ": " + ex.getMessage());
    }
  }

  @Override
  protected String paymentEndpointForTrace(String targetInstitution) {
    return normalizeUrl(participantBaseUrls.get(targetInstitution)) + "/finsim/v1/payments";
  }

  @Override
  protected Optional<LegQueryResult> fetchLegStatus(
      String targetInstitution, String transactionId, String direction) {
    String url = statusEndpointForTrace(targetInstitution, transactionId, direction);
    try {
      LegResponsePayload body = restClient.get().uri(url).retrieve().body(LegResponsePayload.class);
      if (body == null) {
        return Optional.empty();
      }
      Map<String, Object> raw = new LinkedHashMap<>();
      raw.put("transactionId", body.transactionId());
      raw.put("direction", body.direction());
      raw.put("status", body.status());
      if (body.reasonCode() != null) {
        raw.put("reasonCode", body.reasonCode());
      }
      if (body.participantReference() != null) {
        raw.put("participantReference", body.participantReference());
      }
      raw.put("provenance", body.provenance());
      return Optional.of(
          new LegQueryResult(body.status(), body.reasonCode(), body.participantReference(), raw));
    } catch (RestClientResponseException ex) {
      if (ex.getStatusCode().value() == 404) {
        return Optional.empty();
      }
      throw ex;
    }
  }

  @Override
  protected String statusEndpointForTrace(
      String targetInstitution, String transactionId, String direction) {
    return normalizeUrl(participantBaseUrls.get(targetInstitution))
        + "/finsim/v1/payments/"
        + transactionId
        + "?leg="
        + direction;
  }

  @Override
  protected void onDebitConfirmed(
      String institutionId, String accountReference, BigDecimal amount) {
    balances.computeIfPresent(
        institutionId + ":" + accountReference, (k, bal) -> bal.subtract(amount));
  }

  @Override
  protected void onCreditConfirmed(
      String institutionId, String accountReference, BigDecimal amount) {
    balances.computeIfPresent(institutionId + ":" + accountReference, (k, bal) -> bal.add(amount));
  }

  @Override
  protected void onReversalConfirmed(
      String institutionId, String accountReference, BigDecimal amount) {
    balances.computeIfPresent(institutionId + ":" + accountReference, (k, bal) -> bal.add(amount));
  }

  @Override
  public Map<String, BigDecimal> balances() {
    return Map.copyOf(balances);
  }

  private static String normalizeUrl(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
