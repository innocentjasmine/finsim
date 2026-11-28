package io.github.mhnuk2007.finsim.sim;

import java.time.Instant;

/**
 * Handle for a scheduled callback registered on a {@link VirtualClock}.
 *
 * <p>Events execute deterministically by {@code (fireAt, priority, sequenceId)}.
 */
public interface ScheduledEventHandle {

  /** Monotonic sequence counter assigned at registration for FIFO tie-breaking. */
  long sequenceId();

  /** Target virtual instant at which this event is due. */
  Instant fireAt();

  /** Execution priority at identical virtual instants (lower runs first). */
  int priority();

  /** Descriptive event name for traces and diagnostics. */
  String eventName();

  /** Whether this handle was cancelled prior to execution. */
  boolean isCancelled();

  /** Whether this event has already fired. */
  boolean isExecuted();

  /** Whether this event is still pending execution in the clock queue. */
  default boolean isPending() {
    return !isCancelled() && !isExecuted();
  }
}
