package io.github.mhnuk2007.finsim.sim;

/**
 * Transport delivery behavior when a fault triggers on an outbound participant leg call.
 *
 * <p>{@link #BEFORE_PROCESSING} drops or fails the transport before the participant processes the
 * leg; {@link #RESPONSE_LOST_AFTER_PROCESSING} delivers the leg so the participant applies it, then
 * discards the response so FinSim observes a timeout.
 */
public enum FaultBehavior {
  BEFORE_PROCESSING,
  RESPONSE_LOST_AFTER_PROCESSING
}
