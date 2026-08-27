# Architecture Decisions

A record of the decisions made before the first line of code was written, along
with the reasoning behind each one. Anyone arriving later (including me, six
months from now) should read this before proposing a change of direction.

Last reviewed: 2026-08-27

---

## Context

**The problem:** my metrics live on six or seven different platforms. Keeping up
with them means opening six or seven dashboards every single day. Tools that
unify this exist, and they are expensive.

**So the primary goal is unification, not history.** One screen, every platform,
current numbers. If the app did nothing else, it would already pay for itself in
time saved.

**Secondary goal: a unified history.** The platforms do show metrics for free in
their own dashboards — Twitch's Creator Dashboard is genuinely good. But each
one uses its own time window and its own format, and none of them lets me put
Twitch and YouTube on the same axis. Worth noting that the APIs expose *less*
than the dashboards do: analytics-style figures such as average viewers and
hours watched are not in the Twitch API at all, so anything like that has to be
sampled and stored by this app.

Twitch is implemented first because I am an affiliate and its API is the most
accessible. That is build order, not focus.

Users: me (streamer, Twitch affiliate) and the human I live with (tech lead,
software engineer, content creator, born in the storm, first of their name).

---

## 1. Stack

**Decision:** Java 17, Javalin, SQLite, JDBI 3, Maven, Flyway, the native
`java.net.http.HttpClient`, Jackson, slf4j + logback.

**On Java 17 rather than a newer release:** 17 is an LTS, it is what is already
installed on this machine, and it is the minimum Javalin 6 requires. Nothing in
this project needs anything newer. Bumping later is a one-line change in
`pom.xml`.

**Why:**

- **Javalin instead of Spring Boot.** Javalin is a thin layer over Jetty: it
  starts in ~1s, uses little memory, and the request flow is readable end to
  end. Since this is a learning project, seeing what actually happens is worth
  more than the productivity of Spring's annotations.
- **SQLite.** Small time series, single-user access, one file. Nothing here
  justifies a database server.
- **JDBI 3 instead of JPA/Hibernate.** Explicit SQL, no session, no lazy
  loading, no ORM to debug. The SQL in this project is simple and gains clarity
  from being written by hand.
- **`java.net.http.HttpClient` instead of OkHttp/Apache.** Native since Java 11.
  One less dependency and more of the standard library learned.
- **Maven instead of Gradle.** `pom.xml` is declarative and easier to read while
  learning; when a build breaks, Maven has an enormous body of documentation and
  answers behind it.
- **Flyway.** Versioned schema from day one, rather than a loose `CREATE TABLE`
  at startup.

**Accepted trade-off:** Spring Boot is what the job market asks for. This
project is not proof of Spring skills in a portfolio. It compensates by showing
an understanding of fundamentals, and the modular architecture allows migrating
later if that ever makes sense.

---

## 2. Front-end with no build step

**Decision:** plain HTML + CSS + vanilla JavaScript with Chart.js, served as
static files by Javalin itself. No npm, no React, no bundler.

**Why:** the whole selling point of this project is being lightweight. Pulling
in hundreds of megabytes of `node_modules` to display a dozen numbers
contradicts that. And time spent configuring front-end tooling is time not spent
learning Java, which is the actual goal.

**Accepted trade-off:** no componentization, no typing on the front end.
Acceptable for one dashboard screen and a few charts. If the UI grows a lot,
revisit this.

---

## 3. Data freshness is a non-negotiable requirement

**Decision:** the interface **never** displays a number without indicating when
it was fetched. If a fetch fails, the card is visually marked as stale and
**never** shows the last known value as if it were current.

Every response from the internal API carries:

```json
{ "value": 1234, "fetchedAt": "2026-08-27T14:32:11Z", "status": "OK" }
```

`status` ∈ `OK` | `STALE` | `ERROR`.

**Why:** a dashboard that shows an old number without saying so is worse than no
dashboard at all, because it produces confident wrong decisions. A visible
failure is always preferable to a silent one.

**Accepted trade-off:** more state-handling code, and the screen will
occasionally look "broken" when a platform is down. That is the correct
behavior.

---

## 4. Two-path architecture

**Decision:** keep the two data flows completely separate.

| | Live | Historical |
|---|---|---|
| Source | platform API, on demand | SQLite |
| Cache | in memory, 60s TTL (rate limits only) | — |
| Feeds | the current-value cards | the charts only |

**Why:** the obvious temptation is to read everything from the database, but the
database only holds daily snapshots — it would serve data up to 24h old and
violate decision 3.

**Derived rule:** if the live fetch fails, the card shows `ERROR`. There is
**no** fallback to the latest snapshot in the database. That fallback is exactly
the behavior decision 3 forbids.

---

## 5. Target platforms

**Included:** Twitch (first), YouTube (Data API), Bluesky, Discord, Instagram.

**Excluded — X/Twitter:** paid API, basic tier around US$100/month.
Unjustifiable cost for reading one's own metrics. Firm decision, not to be
reopened.

**Instagram specifics:**

- Requires a **Professional** account (Creator or Business) — free conversion
  inside the app.
- Use the **"Instagram API with Instagram Login"** path. If the documentation
  requires a linked Facebook Page, that is the legacy path — look for the newer
  one.
- **App Review is not required:** the app stays in Development Mode and both of
  us are added as *testers*. Review is only required to serve third parties.
- **The token expires in 60 days** and there is no classic refresh token: it is
  a sliding window that must be renewed via an API call. If the app sits idle
  for two months, the token dies and a manual re-login is required.

**Accepted trade-off:** this one genuinely hurts, because unification is the
entire point of the project (see Context) — every excluded platform is one more
tab I still have to open by hand. X is the painful one. The answer is to cover
the reachable platforms properly rather than to cover none, and to be honest on
the dashboard about which networks are simply not there.

---

## 6. Data model — rules that run through the whole schema

The schema lives in `src/main/resources/db/migration/V1__baseline.sql`. The
rules that justify it:

### 6.1. Store only what the API returned; never what was computed

Rates, averages and percentages are computed at query time. A stored derived
value goes stale silently when one of its inputs changes — the same disease as
stale data, only hidden inside the database.

**Single deliberate exception:** `stream_session.peak_viewers` and
`avg_viewers`. The samples they are derived from are deleted after 90 days by
retention policy. When the ingredient is destroyed on purpose, the summary stops
being a cache and becomes primary data.

### 6.2. A gap is the absence of a row

`metric_snapshot.value` is `NOT NULL` and there is no status column. If it was
not collected, no row exists. The chart walks the date range and whatever is
missing becomes a visible hole. **Never interpolate or repeat the last value.**

### 6.3. Every collection attempt is logged, including the failures

The `collection_run` table answers the question you ask while staring at a gap
in the chart — "did it fail, or was my machine simply off?":

- gap + a run with `status='ERROR'` → it tried and failed, with the reason
- gap + no run at all → machine was off, nothing is broken

`error_kind` is categorized because the handling differs: `RATE_LIMIT` and
`NETWORK` are transient; **`AUTH` is an alarm on screen**, the token is dead.

### 6.3.1. A Twitch stream is not a broadcast

Twitch issues a **new stream id** every time a connection drops and comes back.
It treats the two halves of one evening as unrelated streams — confirmed from
the streamer's own experience, and a known annoyance.

Modelling one session per Twitch stream id would therefore shatter a single
evening into several rows, and every average, peak and duration computed from
them would be wrong. So the schema separates the two ideas:

- **`stream_session`** — the broadcast as the streamer experienced it. "Tuesday's
  stream" is one row even if it dropped twice.
- **`stream_segment`** — one Twitch stream id. Several per session when the
  connection was unstable.

`viewer_sample` stays attached to the **session**, so the chart is one
continuous line for the evening with a visible hole where the connection was
down. Averages are computed across all of a session's samples rather than by
averaging per-segment averages, which would weigh a two-minute reconnect the
same as a four-hour stretch.

The sampler decides between "reconnection" and "new broadcast" using the time
since the last sample: inside the ten-minute grace period it is the same
session, beyond it a new one. That threshold is the one knob worth tuning by
taste.

### 6.4. "I don't have the data" must say why

There are two places in the schema where absence would be ambiguous, both
resolved with an explicit marker: decision 6.3 above, and
`stream_session.samples_pruned`, which distinguishes "summarized on purpose"
from "never monitored".

### 6.5. Time zones

`snapshot_date` is a local date (`America/Sao_Paulo`); `captured_at` is a UTC
instant. Always both. Without this there is no way to tell whether a collection
at 23:50 is "late yesterday" or "early today".

### 6.5.1. The metric vocabulary is a closed enum

`MetricKey` in Java, written to the `metric` column as a stable lowercase
string. If Twitch stored `followers` and YouTube stored `subs`, the two could
never appear on the same chart — and comparing platforms side by side is the
reason this project exists. Each provider translates its own API's wording into
the shared vocabulary.

The stored value is the enum's `key()` string rather than its Java name, so
renaming a constant can never silently orphan years of history.

### 6.6. Idempotency

`UNIQUE (account_id, metric, snapshot_date)` plus an upsert. Running the job
twice on the same day updates the row instead of duplicating it. The most recent
collection wins.

### 6.7. Account, not platform

The token belongs to the account, not the platform. Once my partner uses the app
there will be two Twitch accounts with distinct tokens. This is the only place
where groundwork was laid for the future, because the cost now is zero and the
cost later would be a data migration.

---

## 7. Retention and volume

A numeric time series is remarkably cheap: ~1 MB/year of snapshots, ~4 MB/year
of viewer samples. Ten years fit in ~50 MB.

| Data | Retention |
|---|---|
| `metric_snapshot` | **forever** — it is the project's asset |
| `viewer_sample` | 90 days raw, then summarized into the session and deleted |
| `collection_run` | 90 days |
| Raw JSON responses | 7 days, or not stored at all |

Monthly maintenance job: summarize old sessions → delete their samples → delete
old runs → `VACUUM` (without it the file never shrinks).

**What would actually bloat the database** is storing the raw payload of every
response, or per-post data. Both are out of scope for the MVP.

---

## 8. Backups

The `.db` file is irreplaceable: platforms do not sell the past back. A lost
snapshot is lost forever.

- Daily backup via `VACUUM INTO 'backup-YYYY-MM-DD.db'` — the safe way to copy
  SQLite while the app is running, without catching the file mid-write.
- Keep the last 7 copies.
- Monthly CSV export as extra insurance: a CSV can be opened anywhere five years
  from now, even if this project is long dead.

This belongs in the MVP, not in phase 2.

---

## 9. Security

The repository is **public**.

- `client_secret`, tokens and the `.db` file **never** go into Git. `.gitignore`
  was configured before the first commit.
- Configuration lives in `config.properties` (ignored) plus a versioned
  `config.properties.example` with empty keys.
- Tokens are stored in plain text inside SQLite. Acceptable for local use;
  unacceptable if the file leaks. Hence the strictness of the rule above.

**The template file is guarded at startup.** `config.properties` and
`config.properties.example` sit side by side in every file list, differ by one
suffix, and only one of them is git-ignored — with nothing on screen saying
which. Typing a secret into the wrong one is the predictable outcome of that
design, not carelessness, and the consequence is permanent: a credential in git
history survives its own deletion.

So `SecretGuard` refuses to start the application when the template holds a
value under a key containing *secret*, *clientId*, *token* or *password*. Ten
seconds to fix, before it can ever reach a commit. Fixing the design beats
trusting anyone to remember.

---

## 10. Mandatory SQLite configuration

On every connection:

```sql
PRAGMA journal_mode = WAL;    -- readers and writers coexist without blocking
PRAGMA busy_timeout = 5000;   -- wait instead of throwing SQLITE_BUSY
PRAGMA foreign_keys = ON;     -- off by default; without it, FKs are decoration
```

The first keeps the scheduler from fighting the HTTP threads. The other two are
treacherous SQLite defaults.

---

## 11. Operational robustness

- **A powered-off machine must not create a silent gap:** on startup the app
  checks whether today's snapshot is missing and collects it.
- **Orphaned live session:** if the app dies mid-stream, `ended_at` stays NULL
  forever. On startup, close open sessions whose last sample is old, using that
  sample's timestamp as `ended_at`.
- **Token validity:** `oauth_token` has two expiry columns. `access_expires_at`
  is routine (renews itself). `hard_expires_at` is the final deadline requiring
  a manual re-login — Instagram, 60 days. Warn on the dashboard when fewer than
  15 days remain.
- **Scopes are stored** with the token: when a new metric requires a new scope,
  the app compares and says "log in again" instead of throwing a cryptic 403.

---

---

## 12. Modularity without a plugin system

**Decision:** platforms plug in through an interface (`MetricsProvider`), plus a
capability interface (`LiveTrackable`) for the ones that broadcast. They are
registered as a plain list in `Main`. No runtime plugin loading.

**Why:** modularity and a plugin system are not the same thing. A real plugin
system — jars discovered at runtime, isolated classloaders, lifecycle management
— exists so third parties can extend an application *without recompiling it*.
This project has two users, one repository, and a rebuild on every change.
It would pay the entire cost of that machinery and use none of its benefit,
while burying the domain logic under infrastructure and making failures far
harder to debug.

The test: *does anyone need to add a platform without rebuilding the app?* No.
So an interface is enough. Adding YouTube means one new class plus one line in
`Main`, and nothing already written gets touched — which is the whole experience
the plugin idea was reaching for.

**Two capability interfaces rather than one fat one.** `LiveTrackable` is
separate because Bluesky has no concept of a broadcast. One wide interface where
half the methods return null for half the platforms is how an abstraction starts
lying about what it covers.

**The escape hatch, if it is ever needed:** Java's built-in `ServiceLoader`
gives discovery with no classloader work, and the `ServicesResourceTransformer`
already configured in `pom.xml` is exactly what makes it work inside a fat jar.
Not used now — with two providers, an explicit line in `Main` is easier to read
than automatic discovery.

**Also decided here:** the collector separates *what to collect* (per platform)
from *when to collect, what to do on failure, and how to record it* (shared).
The scheduler never learns that Twitch exists.

---

## 13. What Twitch actually exposes

The API gives considerably less than the Creator Dashboard. Knowing which group
a metric falls into decides whether it is a morning's work or impossible.

**Available directly from the API:** live status and current viewer count
(`/streams`), follower total, subscriber total, per-VOD view counts
(`/videos`), per-clip view counts, current chatters (`/chat/chatters`, needs
`moderator:read:chatters`), bits leaderboard.

**Only exists if this application builds it:** average and peak viewers, stream
duration and history, unique chat participants, messages per minute. Twitch does
not expose any of these. They exist only by sampling continuously and storing
the result — which is what `viewer_sample` and `stream_session` are for, and why
the live sampler exists.

**Genuinely impossible:** unique viewers, hours watched, retention, traffic
sources, demographics. Twitch computes these server-side from data that never
leaves their systems. Any tool claiming to offer them is either estimating or
asking the user to paste a CSV.

**The bridge for that last group:** the Creator Dashboard offers CSV exports of
channel analytics. Importing those files would put the otherwise-unreachable
numbers in the same database as everything else, comparable across platforms.
Manual, monthly, and the only honest route. Not built yet.

---

## Open questions

- [ ] **Per-post data.** No table yet. It is what would most help decide what to
  post, and also what grows fastest in volume. The next thing to design after
  the MVP.
- [ ] **Goals.** No table for targets (e.g. Twitch Partner requirements). Small
  table, but it changes the layout of the landing screen — decide before
  designing the UI.
