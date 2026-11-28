package io.github.mhnuk2007.finsim.sim;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Discrete-event virtual clock separating controllable synthetic business time from wall-clock
 * security/transport time (Roadmap Correction 5: "Two Clocks Rule").
 */
public interface VirtualClock {

  /** Creates a virtual clock initialized to {@link Instant#EPOCH}. */
  static VirtualClock create() {
    return new DiscreteVirtualClock(Instant.EPOCH);
  }

  /** Creates a virtual clock initialized to the given deterministic baseline instant. */
  static VirtualClock at(Instant initialTime) {
    return new DiscreteVirtualClock(initialTime);
  }

  /** Current virtual business timestamp. */
  Instant now();

  /** Initial or most recently reset baseline instant. */
  Instant baseline();

  /**
   * Advances virtual time by {@code duration} without physical sleeps and drains scheduled events
   * due {@code <= target}.
   *
   * @return number of scheduled events executed during this advance
   */
  int advanceBy(Duration duration);

  /**
   * Advances virtual time forward to {@code targetInstant} and drains scheduled events due {@code
   * <= targetInstant}.
   *
   * @return number of scheduled events executed during this advance
   */
  int advanceTo(Instant targetInstant);

  /**
   * Schedules a named event callback at {@code fireAt} with deterministic ordering {@code (fireAt,
   * priority, sequenceCounter)}. Lower {@code priority} values execute first.
   */
  ScheduledEventHandle schedule(Instant fireAt, int priority, String eventName, Runnable action);

  /** Convenience helper scheduling an event {@code delay} after the current virtual instant. */
  default ScheduledEventHandle scheduleAfter(
      Duration delay, int priority, String eventName, Runnable action) {
    Objects.requireNonNull(delay, "delay must not be null");
    if (delay.isNegative()) {
      throw new IllegalArgumentException("delay must not be negative: " + delay);
    }
    return schedule(now().plus(delay), priority, eventName, action);
  }

  /** Cancels a pending scheduled event if it has not yet fired or been cancelled. */
  boolean cancel(ScheduledEventHandle handle);

  /** Executes all pending events due at or before {@link #now()}. */
  int drainCurrentEvents();

  /** Number of pending scheduled events currently queued. */
  int pendingEventCount();

  /** Returns a {@link Clock} view in UTC reflecting {@link #now()}. */
  default Clock asJavaClock() {
    return asJavaClock(ZoneOffset.UTC);
  }

  /** Returns a {@link Clock} view in {@code zone} reflecting {@link #now()}. */
  Clock asJavaClock(ZoneId zone);

  /** Captures an immutable snapshot of clock time, sequence counter, and pending events. */
  VirtualClockSnapshot snapshot();

  /** Restores the clock to a previously captured snapshot. */
  void restore(VirtualClockSnapshot snapshot);

  /** Resets virtual time to {@code newBaseline}, clearing pending scheduled events. */
  void reset(Instant newBaseline);

  /** Resets virtual time back to {@link #baseline()}, clearing pending scheduled events. */
  default void reset() {
    reset(baseline());
  }
}
