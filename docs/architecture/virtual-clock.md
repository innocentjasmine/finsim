# Virtual Clock & Time-Travel Engine Architecture

> Implements **Roadmap Correction 5 ("Two Clocks Rule")** and provides the deterministic
> discrete-event temporal foundation for `finsim-sim` (Issue #27).

## 1. The Two Clocks Rule

Financial simulations must test multi-hour cut-off windows, multi-day authorization holds, and
stale-transfer recovery sweeps without blocking JVM threads or introducing timing flakiness. At
the same time, transport metrics, audit telemetry, and security tokens rely on real host time.

FinSim enforces a strict separation between two clocks:

| Clock | Implementation | Used For | Behavior on Time Travel |
| :--- | :--- | :--- | :--- |
| **Virtual Business Clock** | `VirtualClock` / `DiscreteVirtualClock` | Simulation timestamps (`TraceEvent.virtualAt`), scheduled expirations/sweeps, `ADVANCE_TIME` scenario steps, `VirtualClock.asJavaClock()` | Advances instantaneously via discrete jumps (`advanceBy`, `advanceTo`) without `Thread.sleep` |
| **Wall / Observability Clock** | `Clock.systemUTC()` / `System.nanoTime()` | Host event timestamps (`TraceEvent.at`), HTTP/leg telemetry (`durationMs`), diagnostic logs | Never simulated or frozen; remains continuous real time |

The core virtual clock lives in `finsim-sim` (`io.github.mhnuk2007.finsim.sim`) with zero Spring
or JPA dependencies so domain rules, scenario orchestration, and future Spring modules can depend
on it cleanly.

## 2. Discrete-Event Scheduling & Determinism

`DiscreteVirtualClock` maintains a `PriorityQueue` guarded by a `ReentrantLock`. Every scheduled
task is assigned a `ScheduledEventHandle` and ordered strictly by the composite key:

```
(fireAt, priority, sequenceId)
```

1. **`fireAt`** (`Instant`): earliest due timestamp fires first.
2. **`priority`** (`int`): lower integer priority runs first when two events share the exact same
   `fireAt`.
3. **`sequenceId`** (`long`): monotonically increasing registration sequence breaks any remaining
   ties in strict FIFO order.

### Chronological Stepping & Re-entrant Scheduling

When `advanceBy(Duration)` or `advanceTo(Instant)` moves the clock from `T0` to `T1`:

- Due events with `fireAt <= T1` are drained in `(fireAt, priority, sequenceId)` order.
- Before invoking each callback, the clock sets `currentNow` to that event's `fireAt`, ensuring any
  event querying `clock.now()` or calling `scheduleAfter(...)` observes accurate intermediate
  virtual time cleanly.
- If a callback schedules another event due at `<= T1`, it is queued and drained within the same
  advance step at its proper chronological slot.
- Once all due events `<= T1` complete, `currentNow` settles at `T1`.

### Validation Invariants

- Baseline defaults to `Instant.EPOCH` (`VirtualClock.create()`) or an explicit baseline via
  `VirtualClock.at(Instant)`.
- `advanceBy(Duration)` requires strictly positive durations (`> 0`).
- `advanceTo(Instant)` rejects backward movement (`targetInstant < now()`).
- `schedule(Instant, ...)` rejects instants in the past (`fireAt < now()`).
- `scheduleAfter(Duration, ...)` rejects negative delays (`Duration.ZERO` schedules at `now()` for
  the next `drainCurrentEvents()` / advance).

## 3. Scenario DSL (`finsim/scenario/v1`) Integration

### Root `startTime` & `ADVANCE_TIME` Steps

Scenarios in YAML or JSON support:

- Optional root field **`startTime`**: ISO-8601 UTC `Instant` (defaults to `1970-01-01T00:00:00Z`).
- Step action **`ADVANCE_TIME`**: advances `VirtualClock` between payment/resolve steps using
  mutually exclusive fields:
  - `advanceBy`: strictly positive ISO-8601 duration (e.g. `PT30M`, `P7D`)
  - `advanceTo`: non-decreasing ISO-8601 UTC instant (e.g. `2026-01-02T00:00:00Z`)

`BaseScenarioExecutor` initializes a fresh `VirtualClock.at(scenario.effectiveStartTime())` on
every `initialize(ScenarioDefinition)` call and implements `executeAdvanceTime(StepSpec)`:

- Advances `virtualClock` without sleeping.
- Records a `STAGE` (`advance_time`) event and a `TIME_ADVANCED` trace event carrying
  `previousVirtualTime`, `virtualNow`, `advanceBy`/`advanceTo`, and `firedScheduledEvents`.
- Continues measuring `durationMs` via `System.nanoTime()` and stamping `TraceEvent.at` via
  `Clock.systemUTC()`.

### Live CLI Guard

`finsim scenario run --live` targets a running FinSim server executing against wall-clock time.
To prevent silent no-ops or mismatched expectations, `ScenarioCommand` validates before making
network requests and exits with usage error (`ExitCode.USAGE`) if a scenario contains
`ADVANCE_TIME` steps in `--live` mode. Offline `finsim scenario run` and `--verify-replay` execute
`ADVANCE_TIME` deterministically.

## 4. Trace Normalization & Replay Equivalence

- `TraceEvent` carries both `Instant at` (wall clock) and optional `Instant virtualAt` (virtual
  business clock).
- `TraceNormalizer` strips volatile host values (`at`, `durationMs`, `threadId` plus volatile JSON
  payload keys) while preserving `virtualAt` in `NormalizedTraceEvent`.
- `ReplayComparator` compares `virtualAt` alongside canonical event fields and balances, ensuring
  two independent runs of the same scenario match identically and catching any divergence in initial
  `startTime` or time progression.

## 5. Snapshot, Restore, Reset & Spring Wiring Hooks

To support future deterministic fault-injection checkpoints and interactive simulation controls
(e.g. state snapshot/rollback and chaos time-control APIs):

- `VirtualClock.asJavaClock()` / `asJavaClock(ZoneId)` adapts a `VirtualClock` into a standard
  `java.time.Clock` for components accepting `Clock` (such as Spring beans configured with `@Bean
  Clock`).
- `VirtualClockSnapshot snapshot()`, `restore(VirtualClockSnapshot)`, `reset(Instant)`, and
  `pendingEventCount()` capture and restore current time, baseline, monotonic sequence counter, and
  pending scheduled events.
- `VirtualClockFixtures` exports `DEFAULT_BASELINE`, `createDeterministic()`, `createAt(String)`,
  and `EventExecutionLog` for reuse across modules.
