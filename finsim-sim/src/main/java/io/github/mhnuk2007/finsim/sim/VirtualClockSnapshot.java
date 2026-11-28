package io.github.mhnuk2007.finsim.sim;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable capture of a {@link VirtualClock}'s state (current virtual instant, baseline, monotonic
 * sequence counter, and queued scheduled events) for deterministic snapshot/restore.
 */
public final class VirtualClockSnapshot {

  /** Public metadata summary of a pending event in a clock snapshot. */
  public record ScheduledEventSummary(
      long sequenceId, Instant fireAt, int priority, String eventName) {}

  private final Instant now;
  private final Instant baseline;
  private final long nextSequence;
  private final List<DiscreteVirtualClock.ScheduledEntrySnapshot> pendingEntries;

  VirtualClockSnapshot(
      Instant now,
      Instant baseline,
      long nextSequence,
      List<DiscreteVirtualClock.ScheduledEntrySnapshot> pendingEntries) {
    this.now = Objects.requireNonNull(now, "now must not be null");
    this.baseline = Objects.requireNonNull(baseline, "baseline must not be null");
    this.nextSequence = nextSequence;
    this.pendingEntries = List.copyOf(pendingEntries);
  }

  public Instant now() {
    return now;
  }

  public Instant baseline() {
    return baseline;
  }

  public long nextSequence() {
    return nextSequence;
  }

  public int pendingCount() {
    return pendingEntries.size();
  }

  public List<ScheduledEventSummary> pendingEvents() {
    return pendingEntries.stream()
        .map(
            e ->
                new ScheduledEventSummary(
                    e.entry().sequenceId(),
                    e.entry().fireAt(),
                    e.entry().priority(),
                    e.entry().eventName()))
        .toList();
  }

  List<DiscreteVirtualClock.ScheduledEntrySnapshot> entries() {
    return pendingEntries;
  }
}
