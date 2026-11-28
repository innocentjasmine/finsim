package io.github.mhnuk2007.finsim.sim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/**
 * Strips non-deterministic runtime attributes (physical timestamps, duration variances, ephemeral
 * ports, and JVM thread IDs) and canonicalizes trace payloads into SHA-256 digests so scenario runs
 * across isolated environments can be compared deterministically.
 */
public final class TraceNormalizer {

  private static final Set<String> VOLATILE_PAYLOAD_FIELDS =
      Set.of("at", "timestamp", "createdAt", "updatedAt", "durationMs", "threadId");

  private final ObjectMapper canonicalMapper;

  public TraceNormalizer() {
    this.canonicalMapper =
        JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();
  }

  public List<NormalizedTraceEvent> normalize(List<TraceEvent> events) {
    if (events == null || events.isEmpty()) {
      return List.of();
    }
    List<NormalizedTraceEvent> normalized = new ArrayList<>(events.size());
    for (int i = 0; i < events.size(); i++) {
      normalized.add(normalizeEvent(i, events.get(i)));
    }
    return List.copyOf(normalized);
  }

  private NormalizedTraceEvent normalizeEvent(int index, TraceEvent event) {
    return new NormalizedTraceEvent(
        index,
        event.transferId(),
        event.kind(),
        event.stage(),
        event.target(),
        event.httpMethod() != null ? event.httpMethod().toUpperCase(Locale.ROOT) : null,
        normalizePath(event.requestPath()),
        hashPayload(event.request()),
        hashPayload(event.response()),
        event.faultId(),
        event.fromState(),
        event.toState(),
        event.virtualAt());
  }

  /** Strips scheme/host/ephemeral port when a full URL was recorded, preserving path + query. */
  static String normalizePath(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      return null;
    }
    String trimmed = rawPath.trim();
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      URI uri = URI.create(trimmed);
      String path =
          uri.getRawPath() != null && !uri.getRawPath().isEmpty() ? uri.getRawPath() : "/";
      return uri.getRawQuery() != null ? path + "?" + uri.getRawQuery() : path;
    }
    return trimmed;
  }

  String hashPayload(Object payload) {
    if (payload == null) {
      return null;
    }
    JsonNode tree = toJsonTree(payload);
    JsonNode canonical = canonicalizeNode(tree);
    if (canonical == null || canonical.isNull()) {
      return null;
    }
    try {
      String canonicalJson = canonicalMapper.writeValueAsString(canonical);
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("Failed to hash canonical trace payload", e);
    }
  }

  private JsonNode toJsonTree(Object payload) {
    if (payload instanceof String s) {
      String trimmed = s.trim();
      if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
          || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
        try {
          return canonicalMapper.readTree(trimmed);
        } catch (JsonProcessingException ignored) {
          return TextNode.valueOf(s);
        }
      }
      return TextNode.valueOf(s);
    }
    return canonicalMapper.valueToTree(payload);
  }

  private JsonNode canonicalizeNode(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node instanceof ObjectNode obj) {
      TreeMap<String, JsonNode> sorted = new TreeMap<>();
      Iterator<String> fieldNames = obj.fieldNames();
      while (fieldNames.hasNext()) {
        String field = fieldNames.next();
        if (VOLATILE_PAYLOAD_FIELDS.contains(field)) {
          continue;
        }
        sorted.put(field, canonicalizeNode(obj.get(field)));
      }
      ObjectNode out = canonicalMapper.createObjectNode();
      sorted.forEach(out::set);
      return out;
    }
    if (node instanceof ArrayNode arr) {
      ArrayNode out = canonicalMapper.createArrayNode();
      for (JsonNode child : arr) {
        out.add(canonicalizeNode(child));
      }
      return out;
    }
    if (node.isNumber()) {
      BigDecimal bd = node.decimalValue().stripTrailingZeros();
      if (bd.scale() < 0) {
        bd = bd.setScale(0);
      }
      return TextNode.valueOf(bd.toPlainString());
    }
    return node;
  }
}
