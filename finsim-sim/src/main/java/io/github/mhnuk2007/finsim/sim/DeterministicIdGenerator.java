package io.github.mhnuk2007.finsim.sim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Generates domain-separated deterministic step identifiers using UTF-8 SHA-256 without relying on
 * system entropy or random UUID generation.
 */
public final class DeterministicIdGenerator {

  private static final String DOMAIN_PREFIX = "finsim:scenario:v1:";

  private DeterministicIdGenerator() {}

  /**
   * Derives a 32-character lowercase hexadecimal identifier (128 bits of SHA-256) from the scenario
   * seed and 1-based step index.
   */
  public static String derive(long scenarioSeed, int stepIndex) {
    if (stepIndex < 1) {
      throw new IllegalArgumentException("stepIndex must be >= 1");
    }
    String input = DOMAIN_PREFIX + scenarioSeed + ":" + stepIndex;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 32);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm unavailable", e);
    }
  }
}
