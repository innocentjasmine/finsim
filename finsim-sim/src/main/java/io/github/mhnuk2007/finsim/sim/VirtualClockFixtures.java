package io.github.mhnuk2007.finsim.sim;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Reusable {@link VirtualClock} test fixture utilities exported by {@code finsim-sim} for unit,
 * scenario, and integration test suites across FinSim modules.
 */
public final class VirtualClockFixtures {

  /**
   * Default deterministic simulation baseline when scenarios omit an explicit {@code startTime}.
   */
  public static final Instant DEFAULT_BASELINE = Instant.EPOCH;

  /** Recorded execution of a scheduled callback captured at fire time. */
  public record FiredEventRecord(
      Instant virtualFiredAt, int priority, long sequenceId, String eventName) {}

  private VirtualClockFixtures() {}

  /** Creates a virtual clock initialized to {@link #DEFAULT_BASELINE}. */
  public static VirtualClock createDeterministic() {
    return VirtualClock.at(DEFAULT_BASELINE);
  }

  /** Creates a virtual clock initialized to an ISO-8601 instant string. */
  public static VirtualClock createAt(String isoInstant) {
    Objects.requireNonNull(isoInstant, "isoInstant must not be null");
    return VirtualClock.at(Instant.parse(isoInstant));
  }

  /**
   * Thread-safe recorder capturing scheduled event firings along with the exact {@link
   * VirtualClock#now()} instant observed inside each callback.
   */
  public static final class EventExecutionLog {
    private final List<FiredEventRecord> records = new ArrayList<>();

    public synchronized void record(
        VirtualClock clock, int priority, long sequenceId, String eventName) {
      records.add(new FiredEventRecord(clock.now(), priority, sequenceId, eventName));
    }

    public ScheduledEventHandle scheduleLogged(
        VirtualClock clock, Instant fireAt, int priority, String eventName) {
      return scheduleLogged(clock, fireAt, priority, eventName, null);
    }

    public ScheduledEventHandle scheduleLogged(
        VirtualClock clock, Instant fireAt, int priority, String eventName, Runnable callback) {
      long[] seqHolder = new long[1];
      ScheduledEventHandle handle =
          clock.schedule(
              fireAt,
              priority,
              eventName,
              () -> {
                if (callback != null) {
                  callback.run();
                }
                record(clock, priority, seqHolder[0], eventName);
              });
      seqHolder[0] = handle.sequenceId();
      return handle;
    }

    public synchronized List<FiredEventRecord> entries() {
      return Collections.unmodifiableList(new ArrayList<>(records));
    }

    public synchronized List<String> eventNames() {
      return records.stream().map(FiredEventRecord::eventName).toList();
    }

    public synchronized void clear() {
      records.clear();
    }
  }
}
