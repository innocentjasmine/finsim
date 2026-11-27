package io.github.mhnuk2007.finsim.sim;

/** Thrown when a declarative scenario file fails schema or invariant validation. */
public class ScenarioValidationException extends RuntimeException {

  public ScenarioValidationException(String message) {
    super(message);
  }

  public ScenarioValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
