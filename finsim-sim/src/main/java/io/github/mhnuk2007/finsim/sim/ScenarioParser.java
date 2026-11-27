package io.github.mhnuk2007.finsim.sim;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.AccountEndpoint;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.AccountSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.AssertionsSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.BalanceAssertion;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.FaultRuleSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.ParticipantSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.StepAction;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.StepSpec;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.TraceAssertions;
import io.github.mhnuk2007.finsim.sim.ScenarioDefinition.TraceExpectation;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses and validates {@code finsim/scenario/v1} definitions from YAML or JSON. Monetary amounts
 * must be decimal strings and are parsed exclusively into {@link BigDecimal}; floating-point inputs
 * are strictly rejected.
 */
public final class ScenarioParser {

  private static final Pattern DECIMAL_STRING = Pattern.compile("^\\d+(\\.\\d{1,8})?$");

  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "version",
          "name",
          "description",
          "network",
          "seed",
          "participants",
          "faultRules",
          "steps",
          "assertions");
  private static final Set<String> PARTICIPANT_FIELDS = Set.of("id", "accounts");
  private static final Set<String> ACCOUNT_FIELDS =
      Set.of("reference", "initialBalance", "currency", "closed");
  private static final Set<String> FAULT_FIELDS =
      Set.of(
          "id", "target", "stage", "type", "behavior", "delayMs", "probability", "times", "seed");
  private static final Set<String> STEP_FIELDS =
      Set.of(
          "step",
          "action",
          "transactionId",
          "idempotencyKey",
          "targetStep",
          "from",
          "to",
          "amount",
          "currency",
          "reference",
          "expectedState",
          "expectedPendingAction",
          "expectedReasonCode");
  private static final Set<String> ASSERTIONS_FIELDS = Set.of("balances", "trace");
  private static final Set<String> BALANCE_ASSERTION_FIELDS = Set.of("account", "expectedBalance");
  private static final Set<String> TRACE_ASSERTIONS_FIELDS = Set.of("mustIncludeEvents");
  private static final Set<String> TRACE_EVENT_FIELDS =
      Set.of("kind", "stage", "target", "faultId", "fromState", "toState");

  private final ObjectMapper jsonMapper;
  private final ObjectMapper yamlMapper;

  public ScenarioParser() {
    this.jsonMapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    this.yamlMapper =
        new ObjectMapper(new YAMLFactory()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  }

  /** Parses a scenario YAML or JSON file based on file extension or leading content. */
  public ScenarioDefinition parse(Path path) throws IOException {
    if (path == null) {
      throw new ScenarioValidationException("Scenario file path must not be null.");
    }
    String content = Files.readString(path, StandardCharsets.UTF_8);
    String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (fileName.endsWith(".json")) {
      return parseJson(content);
    }
    return parseYaml(content);
  }

  public ScenarioDefinition parseYaml(String yamlContent) {
    return parseWithMapper(yamlMapper, yamlContent, "YAML");
  }

  public ScenarioDefinition parseJson(String jsonContent) {
    return parseWithMapper(jsonMapper, jsonContent, "JSON");
  }

  private ScenarioDefinition parseWithMapper(
      ObjectMapper mapper, String rawContent, String formatName) {
    if (rawContent == null || rawContent.isBlank()) {
      throw new ScenarioValidationException("Scenario %s content is empty.".formatted(formatName));
    }
    JsonNode root;
    try {
      root = mapper.readTree(rawContent);
    } catch (JsonProcessingException e) {
      throw new ScenarioValidationException(
          "Invalid %s scenario syntax: %s".formatted(formatName, e.getOriginalMessage()), e);
    }
    if (!(root instanceof ObjectNode rootObj)) {
      throw new ScenarioValidationException("Scenario root must be an object.");
    }
    rejectUnknownFields(rootObj, ROOT_FIELDS, "scenario");

    String version = requireText(rootObj, "version", "scenario");
    String name = requireText(rootObj, "name", "scenario");
    String description = optionalText(rootObj, "description", "scenario");
    String network = requireText(rootObj, "network", "scenario");
    long seed = requireLong(rootObj, "seed", "scenario");

    List<ParticipantSpec> participants = parseParticipants(rootObj.get("participants"));
    List<FaultRuleSpec> faultRules = parseFaultRules(rootObj.get("faultRules"));
    List<StepSpec> steps = parseSteps(rootObj.get("steps"), seed);
    AssertionsSpec assertions = parseAssertions(rootObj.get("assertions"));

    validateReferences(participants, steps, assertions);

    return new ScenarioDefinition(
        version, name, description, network, seed, participants, faultRules, steps, assertions);
  }

  private static List<ParticipantSpec> parseParticipants(JsonNode node) {
    if (node == null || !node.isArray() || node.isEmpty()) {
      throw new ScenarioValidationException("'participants' must be a non-empty array.");
    }
    List<ParticipantSpec> participants = new ArrayList<>();
    Set<String> seenIds = new HashSet<>();
    for (int i = 0; i < node.size(); i++) {
      JsonNode item = node.get(i);
      String ctx = "participants[" + i + "]";
      if (!(item instanceof ObjectNode obj)) {
        throw new ScenarioValidationException(ctx + " must be an object.");
      }
      rejectUnknownFields(obj, PARTICIPANT_FIELDS, ctx);
      String id = requireText(obj, "id", ctx);
      if (!seenIds.add(id)) {
        throw new ScenarioValidationException("Duplicate participant id '%s'.".formatted(id));
      }
      List<AccountSpec> accounts = parseAccounts(obj.get("accounts"), ctx + ".accounts");
      participants.add(new ParticipantSpec(id, accounts));
    }
    return participants;
  }

  private static List<AccountSpec> parseAccounts(JsonNode node, String ctx) {
    if (node == null || !node.isArray() || node.isEmpty()) {
      throw new ScenarioValidationException(ctx + " must be a non-empty array.");
    }
    List<AccountSpec> accounts = new ArrayList<>();
    Set<String> seenRefs = new HashSet<>();
    for (int i = 0; i < node.size(); i++) {
      JsonNode item = node.get(i);
      String itemCtx = ctx + "[" + i + "]";
      if (!(item instanceof ObjectNode obj)) {
        throw new ScenarioValidationException(itemCtx + " must be an object.");
      }
      rejectUnknownFields(obj, ACCOUNT_FIELDS, itemCtx);
      String ref = requireText(obj, "reference", itemCtx);
      if (!seenRefs.add(ref)) {
        throw new ScenarioValidationException(
            "Duplicate account reference '%s' in %s.".formatted(ref, ctx));
      }
      BigDecimal balance = requireDecimalString(obj, "initialBalance", itemCtx);
      String currency = requireText(obj, "currency", itemCtx);
      boolean closed = optionalBoolean(obj, "closed", itemCtx, false);
      accounts.add(new AccountSpec(ref, balance, currency, closed));
    }
    return accounts;
  }

  private static List<FaultRuleSpec> parseFaultRules(JsonNode node) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new ScenarioValidationException("'faultRules' must be an array.");
    }
    List<FaultRuleSpec> rules = new ArrayList<>();
    Set<String> seenIds = new HashSet<>();
    for (int i = 0; i < node.size(); i++) {
      JsonNode item = node.get(i);
      String ctx = "faultRules[" + i + "]";
      if (!(item instanceof ObjectNode obj)) {
        throw new ScenarioValidationException(ctx + " must be an object.");
      }
      rejectUnknownFields(obj, FAULT_FIELDS, ctx);
      String id = requireText(obj, "id", ctx);
      if (!seenIds.add(id)) {
        throw new ScenarioValidationException("Duplicate faultRule id '%s'.".formatted(id));
      }
      String target = requireText(obj, "target", ctx);
      String stage = optionalText(obj, "stage", ctx);
      FaultType type = parseEnum(requireText(obj, "type", ctx), FaultType.class, ctx + ".type");
      String behaviorRaw = optionalText(obj, "behavior", ctx);
      FaultBehavior behavior =
          behaviorRaw != null
              ? parseEnum(behaviorRaw, FaultBehavior.class, ctx + ".behavior")
              : FaultBehavior.BEFORE_PROCESSING;
      Integer delayMs = optionalInt(obj, "delayMs", ctx);
      BigDecimal probability = optionalDecimal(obj, "probability", ctx);
      Integer times = optionalInt(obj, "times", ctx);
      Long seed = optionalLong(obj, "seed", ctx);
      rules.add(
          new FaultRuleSpec(id, target, stage, type, behavior, delayMs, probability, times, seed));
    }
    return rules;
  }

  private static List<StepSpec> parseSteps(JsonNode node, long scenarioSeed) {
    if (node == null || !node.isArray() || node.isEmpty()) {
      throw new ScenarioValidationException("'steps' must be a non-empty array.");
    }
    List<StepSpec> steps = new ArrayList<>();
    Set<Integer> seenSteps = new HashSet<>();
    for (int i = 0; i < node.size(); i++) {
      JsonNode item = node.get(i);
      String ctx = "steps[" + i + "]";
      if (!(item instanceof ObjectNode obj)) {
        throw new ScenarioValidationException(ctx + " must be an object.");
      }
      rejectUnknownFields(obj, STEP_FIELDS, ctx);
      int stepNum = requireInt(obj, "step", ctx);
      if (!seenSteps.add(stepNum)) {
        throw new ScenarioValidationException("Duplicate step index %d.".formatted(stepNum));
      }
      StepAction action =
          parseEnum(requireText(obj, "action", ctx), StepAction.class, ctx + ".action");
      String transactionId = optionalText(obj, "transactionId", ctx);
      String idempotencyKey = optionalText(obj, "idempotencyKey", ctx);
      Integer targetStep = optionalInt(obj, "targetStep", ctx);

      AccountEndpoint from = null;
      AccountEndpoint to = null;
      BigDecimal amount = null;
      String currency = null;
      String reference = optionalText(obj, "reference", ctx);
      if (action == StepAction.TRANSFER) {
        from = AccountEndpoint.parse(requireText(obj, "from", ctx), ctx + ".from");
        to = AccountEndpoint.parse(requireText(obj, "to", ctx), ctx + ".to");
        amount = requireDecimalString(obj, "amount", ctx);
        currency = requireText(obj, "currency", ctx);
        if (transactionId == null) {
          transactionId = DeterministicIdGenerator.derive(scenarioSeed, stepNum);
        }
        if (idempotencyKey == null) {
          idempotencyKey = DeterministicIdGenerator.derive(scenarioSeed, stepNum);
        }
      }

      String expectedState = optionalText(obj, "expectedState", ctx);
      String expectedPendingAction = optionalText(obj, "expectedPendingAction", ctx);
      String expectedReasonCode = optionalText(obj, "expectedReasonCode", ctx);

      steps.add(
          new StepSpec(
              stepNum,
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
              expectedReasonCode));
    }
    return steps;
  }

  private static AssertionsSpec parseAssertions(JsonNode node) {
    if (node == null || node.isNull()) {
      return AssertionsSpec.empty();
    }
    if (!(node instanceof ObjectNode obj)) {
      throw new ScenarioValidationException("'assertions' must be an object.");
    }
    rejectUnknownFields(obj, ASSERTIONS_FIELDS, "assertions");

    List<BalanceAssertion> balances = new ArrayList<>();
    JsonNode balancesNode = obj.get("balances");
    if (balancesNode != null && !balancesNode.isNull()) {
      if (!balancesNode.isArray()) {
        throw new ScenarioValidationException("'assertions.balances' must be an array.");
      }
      for (int i = 0; i < balancesNode.size(); i++) {
        String ctx = "assertions.balances[" + i + "]";
        JsonNode item = balancesNode.get(i);
        if (!(item instanceof ObjectNode balObj)) {
          throw new ScenarioValidationException(ctx + " must be an object.");
        }
        rejectUnknownFields(balObj, BALANCE_ASSERTION_FIELDS, ctx);
        AccountEndpoint acc =
            AccountEndpoint.parse(requireText(balObj, "account", ctx), ctx + ".account");
        BigDecimal expected = requireDecimalString(balObj, "expectedBalance", ctx);
        balances.add(new BalanceAssertion(acc, expected));
      }
    }

    TraceAssertions trace = new TraceAssertions(List.of());
    JsonNode traceNode = obj.get("trace");
    if (traceNode != null && !traceNode.isNull()) {
      if (!(traceNode instanceof ObjectNode traceObj)) {
        throw new ScenarioValidationException("'assertions.trace' must be an object.");
      }
      rejectUnknownFields(traceObj, TRACE_ASSERTIONS_FIELDS, "assertions.trace");
      JsonNode eventsNode = traceObj.get("mustIncludeEvents");
      List<TraceExpectation> expectations = new ArrayList<>();
      if (eventsNode != null && !eventsNode.isNull()) {
        if (!eventsNode.isArray()) {
          throw new ScenarioValidationException(
              "'assertions.trace.mustIncludeEvents' must be an array.");
        }
        for (int i = 0; i < eventsNode.size(); i++) {
          String ctx = "assertions.trace.mustIncludeEvents[" + i + "]";
          JsonNode item = eventsNode.get(i);
          if (!(item instanceof ObjectNode evtObj)) {
            throw new ScenarioValidationException(ctx + " must be an object.");
          }
          rejectUnknownFields(evtObj, TRACE_EVENT_FIELDS, ctx);
          expectations.add(
              new TraceExpectation(
                  requireText(evtObj, "kind", ctx),
                  optionalText(evtObj, "stage", ctx),
                  optionalText(evtObj, "target", ctx),
                  optionalText(evtObj, "faultId", ctx),
                  optionalText(evtObj, "fromState", ctx),
                  optionalText(evtObj, "toState", ctx)));
        }
      }
      trace = new TraceAssertions(expectations);
    }

    return new AssertionsSpec(balances, trace);
  }

  private static void validateReferences(
      List<ParticipantSpec> participants, List<StepSpec> steps, AssertionsSpec assertions) {
    Set<String> participantIds = new HashSet<>();
    for (ParticipantSpec p : participants) {
      participantIds.add(p.id());
    }
    for (StepSpec step : steps) {
      if (step.action() == StepAction.RESOLVE && step.transactionId() == null) {
        if (step.targetStep() == null) {
          boolean hasPrecedingTransfer =
              steps.stream()
                  .anyMatch(s -> s.step() < step.step() && s.action() == StepAction.TRANSFER);
          if (!hasPrecedingTransfer) {
            throw new ScenarioValidationException(
                "Step %d RESOLVE without 'transactionId' requires a preceding TRANSFER or 'targetStep'."
                    .formatted(step.step()));
          }
        }
      }
      if (step.action() == StepAction.TRANSFER) {
        if (!participantIds.contains(step.from().institutionId())) {
          throw new ScenarioValidationException(
              "Step %d references undeclared 'from' participant '%s'."
                  .formatted(step.step(), step.from().institutionId()));
        }
        if (!participantIds.contains(step.to().institutionId())) {
          throw new ScenarioValidationException(
              "Step %d references undeclared 'to' participant '%s'."
                  .formatted(step.step(), step.to().institutionId()));
        }
      }
    }
    for (BalanceAssertion bal : assertions.balances()) {
      if (!participantIds.contains(bal.account().institutionId())) {
        throw new ScenarioValidationException(
            "Balance assertion references undeclared participant '%s'."
                .formatted(bal.account().institutionId()));
      }
    }
  }

  private static void rejectUnknownFields(ObjectNode obj, Set<String> allowed, String ctx) {
    Iterator<String> names = obj.fieldNames();
    while (names.hasNext()) {
      String field = names.next();
      if (!allowed.contains(field)) {
        throw new ScenarioValidationException("Unknown field '%s' in %s.".formatted(field, ctx));
      }
    }
  }

  private static String requireText(ObjectNode obj, String field, String ctx) {
    JsonNode node = obj.get(field);
    if (node == null || node.isNull()) {
      throw new ScenarioValidationException(
          "Missing required field '%s.%s'.".formatted(ctx, field));
    }
    if (!node.isTextual() || node.asText().isBlank()) {
      throw new ScenarioValidationException(
          "Field '%s.%s' must be a non-blank string.".formatted(ctx, field));
    }
    return node.asText();
  }

  private static String optionalText(ObjectNode obj, String field, String ctx) {
    JsonNode node = obj.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new ScenarioValidationException(
          "Field '%s.%s' must be a string.".formatted(ctx, field));
    }
    return node.asText().isBlank() ? null : node.asText();
  }

  private static BigDecimal requireDecimalString(ObjectNode obj, String field, String ctx) {
    JsonNode node = obj.get(field);
    if (node == null || node.isNull()) {
      throw new ScenarioValidationException(
          "Missing required field '%s.%s'.".formatted(ctx, field));
    }
    if (node.isNumber()) {
      throw new ScenarioValidationException(
          "Field '%s.%s' must be a quoted decimal string, not a floating-point or JSON number."
              .formatted(ctx, field));
    }
    if (!node.isTextual()) {
      throw new ScenarioValidationException(
          "Field '%s.%s' must be a decimal string.".formatted(ctx, field));
    }
    String raw = node.asText();
    if (!DECIMAL_STRING.matcher(raw).matches()) {
      throw new ScenarioValidationException(
          "Field '%s.%s' must match decimal format '^\\d+(\\.\\d{1,8})?$' (got '%s')."
              .formatted(ctx, field, raw));
    }
    return new BigDecimal(raw);
  }

  private static BigDecimal optionalDecimal(ObjectNode obj, String field, String ctx) {
    JsonNode node = obj.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isTextual() || node.isNumber()) {
      try {
        return new BigDecimal(node.asText());
      } catch (NumberFormatException e) {
        throw new ScenarioValidationException(
            "Field '%s.%s' must be a decimal value (got '%s')."
                .formatted(ctx, field, node.asText()));
      }
    }
    throw new ScenarioValidationException(
        "Field '%s.%s' must be a decimal value.".formatted(ctx, field));
  }

  private static long requireLong(ObjectNode obj, String field, String ctx) {
    JsonNode node = obj.get(field);
    if (node == null || node.isNull() || !node.isIntegralNumber()) {
      throw new ScenarioValidationException(
          "Field '%s.%s' must be an integer.".formatted(ctx, field));
    }
    return node.asLong();
  }

  private static Long optionalLong(ObjectNode obj, String field, String ctx) {
    JsonNode node = obj.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isIntegralNumber()) {
      throw new ScenarioValidationException(
          "Field '%s.%s' must be an integer.".formatted(ctx, field));
    }
    return node.asLong();
  }

  private static int requireInt(ObjectNode obj, String field, String ctx) {
    JsonNode node = obj.get(field);
    if (node == null || node.isNull() || !node.isIntegralNumber() || !node.canConvertToInt()) {
      throw new ScenarioValidationException(
          "Field '%s.%s' must be a 32-bit integer.".formatted(ctx, field));
    }
    return node.asInt();
  }

  private static Integer optionalInt(ObjectNode obj, String field, String ctx) {
    JsonNode node = obj.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      throw new ScenarioValidationException(
          "Field '%s.%s' must be a 32-bit integer.".formatted(ctx, field));
    }
    return node.asInt();
  }

  private static boolean optionalBoolean(
      ObjectNode obj, String field, String ctx, boolean defaultValue) {
    JsonNode node = obj.get(field);
    if (node == null || node.isNull()) {
      return defaultValue;
    }
    if (!node.isBoolean()) {
      throw new ScenarioValidationException(
          "Field '%s.%s' must be a boolean.".formatted(ctx, field));
    }
    return node.asBoolean();
  }

  private static <E extends Enum<E>> E parseEnum(String value, Class<E> enumClass, String ctx) {
    try {
      return Enum.valueOf(enumClass, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ScenarioValidationException("Invalid value '%s' for %s.".formatted(value, ctx), e);
    }
  }
}
