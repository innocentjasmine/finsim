package io.github.mhnuk2007.finsim.sim;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe, zero-dependency discrete-event {@link VirtualClock} implementation.
 *
 * <p>Events fire strictly in {@code (fireAt, priority, sequenceId)} order without {@code
 * Thread.sleep}. Advancing virtual time steps {@link #now()} chronologically to each due event's
 * {@code fireAt} instant before invoking its callback so cascaded events observe accurate
 * intermediate virtual timestamps.
 */
public final class DiscreteVirtualClock implements VirtualClock {

  private static final Comparator<ScheduledEntry> ENTRY_ORDER =
      Comparator.comparing(ScheduledEntry::fireAt)
          .thenComparingInt(ScheduledEntry::priority)
          .thenComparingLong(ScheduledEntry::sequenceId);

  private final ReentrantLock lock = new ReentrantLock();
  private final PriorityQueue<ScheduledEntry> queue = new PriorityQueue<>(ENTRY_ORDER);

  private Instant baseline;
  private Instant currentNow;
  private long sequenceCounter;

  public DiscreteVirtualClock() {
    this(Instant.EPOCH);
  }

  public DiscreteVirtualClock(Instant initialTime) {
    Objects.requireNonNull(initialTime, "initialTime must not be null");
    this.baseline = initialTime;
    this.currentNow = initialTime;
    this.sequenceCounter = 0L;
  }

  @Override
  public Instant now() {
    lock.lock();
    try {
      return currentNow;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Instant baseline() {
    lock.lock();
    try {
      return baseline;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int advanceBy(Duration duration) {
    Objects.requireNonNull(duration, "duration must not be null");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(
          "advanceBy duration must be strictly positive: " + duration);
    }
    lock.lock();
    try {
      Instant target = currentNow.plus(duration);
      return advanceToInternal(target);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int advanceTo(Instant targetInstant) {
    Objects.requireNonNull(targetInstant, "targetInstant must not be null");
    lock.lock();
    try {
      if (targetInstant.isBefore(currentNow)) {
        throw new IllegalArgumentException(
            "Cannot move virtual clock backwards from %s to %s"
                .formatted(currentNow, targetInstant));
      }
      return advanceToInternal(targetInstant);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public ScheduledEventHandle schedule(
      Instant fireAt, int priority, String eventName, Runnable action) {
    Objects.requireNonNull(fireAt, "fireAt must not be null");
    if (eventName == null || eventName.isBlank()) {
      throw new IllegalArgumentException("eventName must not be blank");
    }
    Objects.requireNonNull(action, "action must not be null");

    lock.lock();
    try {
      if (fireAt.isBefore(currentNow)) {
        throw new IllegalArgumentException(
            "Cannot schedule event '%s' in the past: fireAt=%s < now=%s"
                .formatted(eventName, fireAt, currentNow));
      }
      long seq = ++sequenceCounter;
      ScheduledEntry entry = new ScheduledEntry(this, seq, fireAt, priority, eventName, action);
      queue.add(entry);
      return entry;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean cancel(ScheduledEventHandle handle) {
    if (!(handle instanceof ScheduledEntry entry) || entry.owner != this) {
      return false;
    }
    lock.lock();
    try {
      if (entry.cancelled || entry.executed) {
        return false;
      }
      entry.cancelled = true;
      queue.remove(entry);
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int drainCurrentEvents() {
    lock.lock();
    try {
      return drainDueUpTo(currentNow);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int pendingEventCount() {
    lock.lock();
    try {
      return queue.size();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Clock asJavaClock(ZoneId zone) {
    Objects.requireNonNull(zone, "zone must not be null");
    return new VirtualJavaClock(this, zone);
  }

  @Override
  public VirtualClockSnapshot snapshot() {
    lock.lock();
    try {
      List<ScheduledEntrySnapshot> entries = new ArrayList<>(queue.size());
      queue.stream()
          .sorted(ENTRY_ORDER)
          .forEach(e -> entries.add(new ScheduledEntrySnapshot(e, e.cancelled, e.executed)));
      return new VirtualClockSnapshot(currentNow, baseline, sequenceCounter, entries);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void restore(VirtualClockSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    lock.lock();
    try {
      queue.clear();
      this.currentNow = snapshot.now();
      this.baseline = snapshot.baseline();
      this.sequenceCounter = snapshot.nextSequence();
      for (ScheduledEntrySnapshot saved : snapshot.entries()) {
        ScheduledEntry entry = saved.entry();
        if (entry.owner != this) {
          throw new IllegalArgumentException("Snapshot belongs to a different VirtualClock.");
        }
        entry.cancelled = saved.cancelled();
        entry.executed = saved.executed();
        if (!entry.cancelled && !entry.executed) {
          queue.add(entry);
        }
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void reset(Instant newBaseline) {
    Objects.requireNonNull(newBaseline, "newBaseline must not be null");
    lock.lock();
    try {
      for (ScheduledEntry entry : queue) {
        entry.cancelled = true;
      }
      queue.clear();
      this.baseline = newBaseline;
      this.currentNow = newBaseline;
      this.sequenceCounter = 0L;
    } finally {
      lock.unlock();
    }
  }

  private int advanceToInternal(Instant target) {
    int fired = drainDueUpTo(target);
    if (target.isAfter(currentNow)) {
      currentNow = target;
    }
    return fired;
  }

  private int drainDueUpTo(Instant target) {
    int count = 0;
    while (!queue.isEmpty()) {
      ScheduledEntry next = queue.peek();
      if (next.cancelled) {
        queue.poll();
        continue;
      }
      if (next.fireAt.isAfter(target)) {
        break;
      }
      queue.poll();
      if (next.fireAt.isAfter(currentNow)) {
        currentNow = next.fireAt;
      }
      next.executed = true;
      count++;
      next.action.run();
    }
    return count;
  }

  record ScheduledEntrySnapshot(ScheduledEntry entry, boolean cancelled, boolean executed) {}

  static final class ScheduledEntry implements ScheduledEventHandle {
    private final DiscreteVirtualClock owner;
    private final long sequenceId;
    private final Instant fireAt;
    private final int priority;
    private final String eventName;
    private final Runnable action;
    private boolean cancelled;
    private boolean executed;

    ScheduledEntry(
        DiscreteVirtualClock owner,
        long sequenceId,
        Instant fireAt,
        int priority,
        String eventName,
        Runnable action) {
      this.owner = owner;
      this.sequenceId = sequenceId;
      this.fireAt = fireAt;
      this.priority = priority;
      this.eventName = eventName;
      this.action = action;
    }

    @Override
    public long sequenceId() {
      return sequenceId;
    }

    @Override
    public Instant fireAt() {
      return fireAt;
    }

    @Override
    public int priority() {
      return priority;
    }

    @Override
    public String eventName() {
      return eventName;
    }

    @Override
    public boolean isCancelled() {
      return owner.isEntryCancelled(this);
    }

    @Override
    public boolean isExecuted() {
      return owner.isEntryExecuted(this);
    }
  }

  private boolean isEntryCancelled(ScheduledEntry entry) {
    lock.lock();
    try {
      return entry.cancelled;
    } finally {
      lock.unlock();
    }
  }

  private boolean isEntryExecuted(ScheduledEntry entry) {
    lock.lock();
    try {
      return entry.executed;
    } finally {
      lock.unlock();
    }
  }

  private static final class VirtualJavaClock extends Clock {
    private final VirtualClock delegate;
    private final ZoneId zone;

    VirtualJavaClock(VirtualClock delegate, ZoneId zone) {
      this.delegate = delegate;
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new VirtualJavaClock(delegate, Objects.requireNonNull(zone, "zone must not be null"));
    }

    @Override
    public Instant instant() {
      return delegate.now();
    }
  }
}
