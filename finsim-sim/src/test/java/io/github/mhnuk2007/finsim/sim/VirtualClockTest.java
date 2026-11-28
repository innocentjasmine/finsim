package io.github.mhnuk2007.finsim.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mhnuk2007.finsim.sim.VirtualClockFixtures.EventExecutionLog;
import io.github.mhnuk2007.finsim.sim.VirtualClockFixtures.FiredEventRecord;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VirtualClockTest {

  @Test
  void timeTravelThirtyDaysCompletesInstantaneouslyWithoutWallClockSleep() {
    // Warm up classes before timing to avoid cold class-loading skew.
    VirtualClock warmup = VirtualClockFixtures.createDeterministic();
    warmup.scheduleAfter(Duration.ofHours(1), 0, "warmup", () -> {});
    warmup.advanceBy(Duration.ofDays(1));

    Instant startWall = Instant.now();
    VirtualClock clock = VirtualClockFixtures.createAt("2026-01-01T00:00:00Z");
    AtomicInteger fired = new AtomicInteger();
    clock.scheduleAfter(Duration.ofDays(7), 0, "week-1", fired::incrementAndGet);
    clock.scheduleAfter(Duration.ofDays(30), 0, "day-30", fired::incrementAndGet);

    long startNs = System.nanoTime();
    int count = clock.advanceBy(Duration.ofDays(30));
    long elapsedNs = System.nanoTime() - startNs;
    Instant endWall = Instant.now();

    assertThat(count).isEqualTo(2);
    assertThat(fired.get()).isEqualTo(2);
    assertThat(clock.now()).isEqualTo(Instant.parse("2026-01-31T00:00:00Z"));
    assertThat(Duration.ofNanos(elapsedNs)).isLessThan(Duration.ofMillis(5));
    // Two Clocks guarantee: host wall clock does not move forward by 30 days.
    assertThat(Duration.between(startWall, endWall)).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void collisionOrderingForHundredSameMillisecondEventsIsDeterministicAcrossTenRuns() {
    List<List<FiredEventRecord>> allRuns = new ArrayList<>();
    Instant baseline = Instant.parse("2026-03-15T12:00:00Z");
    Instant dueAt = baseline.plusSeconds(10);

    for (int run = 0; run < 10; run++) {
      VirtualClock clock = VirtualClock.at(baseline);
      EventExecutionLog log = new EventExecutionLog();

      // Interleave priorities across 100 events sharing exact identical fireAt millisecond.
      for (int i = 0; i < 100; i++) {
        int priority = 9 - (i % 10);
        String name = "evt-" + i + "-p" + priority;
        log.scheduleLogged(clock, dueAt, priority, name);
      }

      int executed = clock.advanceBy(Duration.ofSeconds(10));
      assertThat(executed).isEqualTo(100);
      assertThat(clock.pendingEventCount()).isZero();
      allRuns.add(log.entries());
    }

    List<FiredEventRecord> firstRun = allRuns.get(0);
    assertThat(firstRun).hasSize(100);

    // Lower priority executes first; identical priority follows ascending sequenceId FIFO order.
    for (int i = 1; i < firstRun.size(); i++) {
      FiredEventRecord prev = firstRun.get(i - 1);
      FiredEventRecord curr = firstRun.get(i);
      assertThat(prev.virtualFiredAt()).isEqualTo(dueAt);
      assertThat(curr.virtualFiredAt()).isEqualTo(dueAt);
      assertThat(prev.priority()).isLessThanOrEqualTo(curr.priority());
      if (prev.priority() == curr.priority()) {
        assertThat(prev.sequenceId()).isLessThan(curr.sequenceId());
      }
    }

    for (int run = 1; run < allRuns.size(); run++) {
      assertThat(allRuns.get(run)).containsExactlyElementsOf(firstRun);
    }
  }

  @Test
  void chronologicalSteppingAndReentrantCascadedSchedulingObserveIntermediateNow() {
    VirtualClock clock = VirtualClockFixtures.createDeterministic();
    EventExecutionLog log = new EventExecutionLog();

    log.scheduleLogged(
        clock,
        Instant.EPOCH.plusSeconds(5),
        0,
        "parent-5s",
        () -> {
          // Observed virtual time inside callback must match the event's fireAt (T+5s).
          assertThat(clock.now()).isEqualTo(Instant.EPOCH.plusSeconds(5));
          // Re-entrant schedule due within the same advance window (T+12s <= T+20s).
          log.scheduleLogged(clock, clock.now().plusSeconds(7), 0, "child-12s");
          // Re-entrant schedule beyond target horizon (T+25s > T+20s) stays pending.
          log.scheduleLogged(clock, clock.now().plusSeconds(20), 0, "future-25s");
        });

    log.scheduleLogged(clock, Instant.EPOCH.plusSeconds(15), -1, "sibling-15s");

    int executed = clock.advanceBy(Duration.ofSeconds(20));

    assertThat(executed).isEqualTo(3);
    assertThat(clock.now()).isEqualTo(Instant.EPOCH.plusSeconds(20));
    assertThat(clock.pendingEventCount()).isEqualTo(1);
    assertThat(log.eventNames()).containsExactly("parent-5s", "child-12s", "sibling-15s");
    assertThat(log.entries().get(1).virtualFiredAt()).isEqualTo(Instant.EPOCH.plusSeconds(12));
  }

  @Test
  void cancellationDrainAndHandleLifecycleAreAccurate() {
    VirtualClock clock1 = VirtualClock.create();
    VirtualClock clock2 = VirtualClock.create();
    List<String> fired = new ArrayList<>();

    ScheduledEventHandle immediate =
        clock1.scheduleAfter(Duration.ZERO, 0, "now-event", () -> fired.add("now-event"));
    ScheduledEventHandle toCancel =
        clock1.scheduleAfter(Duration.ofSeconds(5), 1, "cancelled", () -> fired.add("cancelled"));
    ScheduledEventHandle keeper =
        clock1.scheduleAfter(Duration.ofSeconds(5), 2, "kept", () -> fired.add("kept"));
    ScheduledEventHandle foreign =
        clock2.scheduleAfter(Duration.ofSeconds(5), 0, "foreign", () -> {});

    assertThat(toCancel.isPending()).isTrue();
    assertThat(toCancel.eventName()).isEqualTo("cancelled");
    assertThat(toCancel.priority()).isEqualTo(1);
    assertThat(toCancel.fireAt()).isEqualTo(Instant.EPOCH.plusSeconds(5));

    assertThat(clock1.cancel(foreign)).isFalse();
    assertThat(clock1.cancel(toCancel)).isTrue();
    assertThat(clock1.cancel(toCancel)).isFalse();
    assertThat(toCancel.isCancelled()).isTrue();
    assertThat(toCancel.isPending()).isFalse();

    int drainedNow = clock1.drainCurrentEvents();
    assertThat(drainedNow).isEqualTo(1);
    assertThat(immediate.isExecuted()).isTrue();
    assertThat(immediate.isPending()).isFalse();
    assertThat(clock1.cancel(immediate)).isFalse();

    int advanced = clock1.advanceTo(Instant.EPOCH.plusSeconds(5));
    assertThat(advanced).isEqualTo(1);
    assertThat(keeper.isExecuted()).isTrue();
    assertThat(fired).containsExactly("now-event", "kept");
  }

  @Test
  void validationRejectsInvalidDurationsBackwardAdvancesPastSchedulesAndForeignRestores() {
    VirtualClock clock = VirtualClock.at(Instant.parse("2026-06-01T10:00:00Z"));
    clock.advanceBy(Duration.ofMinutes(5));

    assertThatThrownBy(() -> clock.advanceBy(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> clock.advanceBy(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> clock.advanceTo(Instant.parse("2026-06-01T10:04:59Z")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> clock.schedule(Instant.parse("2026-06-01T10:04:00Z"), 0, "past", () -> {}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> clock.scheduleAfter(Duration.ofMillis(-1), 0, "neg", () -> {}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> clock.scheduleAfter(Duration.ofSeconds(1), 0, "   ", () -> {}))
        .isInstanceOf(IllegalArgumentException.class);

    VirtualClock other = VirtualClock.create();
    other.scheduleAfter(Duration.ofSeconds(1), 0, "other-evt", () -> {});
    VirtualClockSnapshot otherSnapshot = other.snapshot();
    assertThatThrownBy(() -> clock.restore(otherSnapshot))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void javaClockAdapterReflectsVirtualTimeAcrossZones() {
    VirtualClock clock = VirtualClock.at(Instant.parse("2026-01-01T00:00:00Z"));
    Clock utcClock = clock.asJavaClock();
    Clock tokyoClock = utcClock.withZone(ZoneId.of("Asia/Tokyo"));

    assertThat(utcClock.getZone()).isEqualTo(ZoneOffset.UTC);
    assertThat(tokyoClock.getZone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
    assertThat(Instant.now(utcClock)).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(Instant.now(tokyoClock)).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));

    clock.advanceBy(Duration.ofHours(48));
    assertThat(Instant.now(utcClock)).isEqualTo(Instant.parse("2026-01-03T00:00:00Z"));
    assertThat(utcClock.millis()).isEqualTo(Instant.parse("2026-01-03T00:00:00Z").toEpochMilli());
  }

  @Test
  void cardAuthorizationHoldExpiresAfterSevenDaysUnlessCaptured() {
    VirtualClock clock = VirtualClock.at(Instant.parse("2026-02-01T09:00:00Z"));
    BigDecimal totalBalance = new BigDecimal("500.00");
    BigDecimal holdAmount = new BigDecimal("125.00");

    class HoldAggregate {
      String status = "AUTHORIZED";
      BigDecimal available = totalBalance.subtract(holdAmount);
      final ScheduledEventHandle expiryHandle;

      HoldAggregate() {
        this.expiryHandle =
            clock.scheduleAfter(
                Duration.ofDays(7),
                0,
                "auth-hold-expiry",
                () -> {
                  if ("AUTHORIZED".equals(status)) {
                    status = "EXPIRED";
                    available = available.add(holdAmount);
                  }
                });
      }

      void capture() {
        status = "CAPTURED";
        clock.cancel(expiryHandle);
      }
    }

    // Uncaptured hold expires at day 7 instantaneously.
    HoldAggregate uncaptured = new HoldAggregate();
    clock.advanceBy(Duration.ofDays(6));
    assertThat(uncaptured.status).isEqualTo("AUTHORIZED");
    assertThat(uncaptured.available).isEqualByComparingTo("375.00");

    clock.advanceBy(Duration.ofDays(2));
    assertThat(uncaptured.status).isEqualTo("EXPIRED");
    assertThat(uncaptured.available).isEqualByComparingTo("500.00");

    // Captured hold cancels expiry callback so advancing past 7 days keeps it CAPTURED.
    HoldAggregate captured = new HoldAggregate();
    clock.advanceBy(Duration.ofHours(2));
    captured.capture();
    clock.advanceBy(Duration.ofDays(14));
    assertThat(captured.status).isEqualTo("CAPTURED");
    assertThat(captured.available).isEqualByComparingTo("375.00");
  }

  @Test
  void staleUnknownTransferIsSweptDeterministicallyUsingVirtualClock() {
    ScenarioDefinition scenario =
        new ScenarioParser()
            .parseYaml(
                """
                version: "finsim/scenario/v1"
                name: "stale-unknown-sweep"
                network: "sandbox"
                seed: 11
                startTime: "2026-05-01T00:00:00Z"
                participants:
                  - id: "bank-a"
                    accounts:
                      - reference: "ACC-A"
                        initialBalance: "1000.00"
                        currency: "USD"
                  - id: "bank-b"
                    accounts:
                      - reference: "ACC-B"
                        initialBalance: "100.00"
                        currency: "USD"
                faultRules:
                  - id: "credit-lost"
                    target: "bank-b"
                    stage: "credit_destination"
                    type: "TIMEOUT"
                    behavior: "BEFORE_PROCESSING"
                    times: 1
                steps:
                  - step: 1
                    action: "TRANSFER"
                    from: "bank-a:ACC-A"
                    to: "bank-b:ACC-B"
                    amount: "150.00"
                    currency: "USD"
                """);

    InMemoryScenarioEnvironment env = new InMemoryScenarioEnvironment();
    env.initialize(scenario);
    ScenarioExecutor.StepOutcome txOutcome =
        env.executeTransfer(scenario.steps().get(0), "tx-stale-1", "idem-stale-1");
    assertThat(txOutcome.state()).isEqualTo("UNKNOWN");

    AtomicReference<ScenarioExecutor.StepOutcome> sweepOutcome = new AtomicReference<>();
    env.virtualClock()
        .scheduleAfter(
            Duration.ofMinutes(30),
            0,
            "stale-unknown-sweep",
            () ->
                sweepOutcome.set(
                    env.executeResolve(scenario.steps().get(0), txOutcome.transactionId())));

    int fired = env.virtualClock().advanceBy(Duration.ofMinutes(30));

    assertThat(fired).isEqualTo(1);
    assertThat(sweepOutcome.get()).isNotNull();
    assertThat(sweepOutcome.get().state()).isEqualTo("COMPLETED");
    assertThat(env.balances())
        .containsEntry("bank-a:ACC-A", new BigDecimal("850.00"))
        .containsEntry("bank-b:ACC-B", new BigDecimal("250.00"));
    assertThat(env.trace())
        .anySatisfy(
            e -> {
              assertThat(e.stage()).isEqualTo("resolve_credit");
              assertThat(e.virtualAt()).isEqualTo(Instant.parse("2026-05-01T00:30:00Z"));
            });
  }

  @Test
  void snapshotRestoreAndResetPreserveDeterministicReplayState() {
    VirtualClock clock = VirtualClockFixtures.createAt("2026-04-01T00:00:00Z");
    EventExecutionLog log = new EventExecutionLog();

    log.scheduleLogged(clock, clock.now().plusSeconds(10), 0, "evt-10s");
    log.scheduleLogged(clock, clock.now().plusSeconds(30), 1, "evt-30s");

    clock.advanceBy(Duration.ofSeconds(15));
    assertThat(log.eventNames()).containsExactly("evt-10s");

    VirtualClockSnapshot snap = clock.snapshot();
    assertThat(snap.now()).isEqualTo(Instant.parse("2026-04-01T00:00:15Z"));
    assertThat(snap.baseline()).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
    assertThat(snap.nextSequence()).isEqualTo(2L);
    assertThat(snap.pendingCount()).isEqualTo(1);
    assertThat(snap.pendingEvents()).hasSize(1);
    assertThat(snap.pendingEvents().get(0).eventName()).isEqualTo("evt-30s");

    // Advance past evt-30s and verify it fired.
    clock.advanceBy(Duration.ofSeconds(20));
    assertThat(log.eventNames()).containsExactly("evt-10s", "evt-30s");

    // Restore to snapshot -> evt-30s is pending again and fires again on re-advance.
    clock.restore(snap);
    assertThat(clock.now()).isEqualTo(Instant.parse("2026-04-01T00:00:15Z"));
    assertThat(clock.pendingEventCount()).isEqualTo(1);
    log.clear();
    clock.advanceBy(Duration.ofSeconds(20));
    assertThat(log.eventNames()).containsExactly("evt-30s");

    // Schedule and reset back to baseline clears pending events cleanly.
    ScheduledEventHandle pendingBeforeReset =
        clock.scheduleAfter(Duration.ofMinutes(5), 0, "cleared", () -> {});
    clock.reset();
    assertThat(clock.now()).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
    assertThat(clock.pendingEventCount()).isZero();
    assertThat(pendingBeforeReset.isCancelled()).isTrue();
  }

  @Test
  void concurrentSchedulingMaintainsThreadSafetyAndExactEventCount() throws Exception {
    VirtualClock clock = VirtualClock.create();
    AtomicInteger executed = new AtomicInteger();
    List<Long> sequences = Collections.synchronizedList(new ArrayList<>());

    int threads = 8;
    int perThread = 50;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int t = 0; t < threads; t++) {
        final int worker = t;
        pool.submit(
            () -> {
              ready.countDown();
              try {
                start.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              for (int i = 0; i < perThread; i++) {
                ScheduledEventHandle handle =
                    clock.scheduleAfter(
                        Duration.ofSeconds(1 + (i % 5)),
                        worker,
                        "w" + worker + "-" + i,
                        executed::incrementAndGet);
                sequences.add(handle.sequenceId());
              }
            });
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(sequences).hasSize(threads * perThread).doesNotHaveDuplicates();
    assertThat(clock.pendingEventCount()).isEqualTo(threads * perThread);

    int totalFired = clock.advanceBy(Duration.ofSeconds(10));
    assertThat(totalFired).isEqualTo(threads * perThread);
    assertThat(executed.get()).isEqualTo(threads * perThread);
  }
}
