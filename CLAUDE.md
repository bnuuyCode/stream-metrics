# stream-metrics

Local social media metrics aggregator. Java 17 + Javalin + SQLite + JDBI + Flyway.
Runs on one machine, serves a dashboard on port 7000. Public GitHub repository.

Full reasoning behind every decision: `docs/DECISIONS.md`. Read it before
proposing an architectural change.

## Commands

```
mvn clean package              # build + tests
java -jar target/stream-metrics.jar
```

In IntelliJ: run `Main`, or `Ctrl+F9` to rebuild. Maven is not on PATH — it ships
inside the IDE.

## The rule that outranks everything

**No number reaches the screen without saying how fresh it is.** Every API
response travels in `ApiResponse<T>` carrying `value`, `fetchedAt` and
`status` (`OK` / `STALE` / `ERROR`).

An error carries `value = null`. There is no overload that lets it carry a
number, and adding one breaks `ApiResponseTest`. That is intentional: the rule
lives in the type so it cannot be forgotten on a tired evening.

If a convenient shortcut would put a possibly-stale number on screen unlabelled,
the shortcut is wrong — no matter how much simpler it is.

## Conventions that are easy to break by accident

- **Documentation and code comments in English.** Conversation with the user is
  in Portuguese; the repository is not.
- **Gaps are the absence of a row.** Never interpolate, never repeat the last
  known value, never backfill. `metric_snapshot.value` is `NOT NULL` and has no
  status column for this reason.
- **Never store a computed value.** Rates and averages are calculated at query
  time. The single deliberate exception is `stream_session.peak_viewers` /
  `avg_viewers`, allowed only because the samples behind them are deleted at 90
  days.
- **Timestamps are ISO-8601 TEXT.** `snapshot_date` is a local date in the
  configured timezone; `captured_at` is a UTC instant. Always both.
- **A Twitch stream id is not a broadcast.** Twitch issues a new id whenever a
  connection drops. `stream_session` is the broadcast; `stream_segment` is each
  Twitch id within it.
- **New platforms implement `MetricsProvider`** (plus `LiveTrackable` if they
  broadcast) and get one line in `Main`. Do not build a plugin system.
- **Schema changes are new Flyway migrations.** Never edit an applied one.

## Never

- Put real credentials in `config.properties.example` — it is versioned and
  public. `SecretGuard` blocks startup if you do.
- Commit `config.properties`, `*.db`, or anything under `backups/`.
- Add a `<developers>` block with an email to `pom.xml`.
- Fall back to a database snapshot when a live fetch fails. Show `ERROR`.

## Still missing

Charts over the collected history, automatic backups, and the platforms beyond
Twitch. See the open questions at the end of `docs/DECISIONS.md`.
