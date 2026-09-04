# Architecture Decisions

A record of the decisions made before the first line of code was written, along
with the reasoning behind each one. Anyone arriving later (including me, six
months from now) should read this before proposing a change of direction.

Last reviewed: 2026-09-01

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

**Included, in the order they will be built:** Twitch (done), Instagram (next),
TikTok, YouTube (Data API), possibly Threads.

**Dropped from the original list on 2026-09-01, for lack of anything to
measure:** Bluesky, where there is no account, and Discord, where the server is
empty. Both were listed on day one on the assumption they would be used. A
platform with no activity would produce a card of zeroes, which reads as a
collection failure rather than as an accurate report of nothing.

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

## 5.1. TikTok is reachable through Sandbox, not through approval

**Checked on 2026-09-02, against TikTok's own developer documentation.** Written
down because this was researched once before and never recorded, which meant it
had to be researched again.

**Correcting an earlier claim made in conversation:** that TikTok blocks all
access until an app passes review, and that starting there risks waiting on
someone else's queue. That is wrong. Sandbox Mode exists precisely to remove
that wait.

**What Sandbox is:** a way to run a real integration without submitting the app
for review. Up to five sandboxes per app, and up to ten *target users* — TikTok
accounts added by supplying their login credentials, so in practice accounts the
developer owns. Which is exactly the shape of this project: one person, their own
account.

**What Sandbox explicitly excludes**, and the exclusion list is short: the
Content Posting API for public videos, and the Data Portability API. The User
Info and video-listing APIs this project needs are not on that list.

**The scopes that matter here:**

| Scope | What it returns |
|---|---|
| `user.info.basic` | open id, avatar, display name. Added by default with Login Kit |
| `user.info.stats` | follower count, following count, likes count, video count |
| `video.list` | the account's public videos |

`user.info.stats` is the daily-totals equivalent of what Twitch gives, and
`video.list` is the per-post path that § "Open questions" has been waiting on.

**What the documentation does not say**, and therefore what this section does not
claim: whether Sandbox returns fully real figures or reduced ones for these
endpoints, and whether a sandbox expires. Neither is stated anywhere official.
Both are cheap to settle by creating the app and looking, which is the reason to
do that early rather than late.

**App review is still required to leave Sandbox** — that is, to serve anyone who
is not a target user. This project has no such ambition, so review may never be
needed at all. A third-party guide put a clean first submission at one to two
weeks in 2026; that figure is not from TikTok and is recorded as hearsay.

**Consequence for ordering:** Instagram still goes first, but for the
architectural reason rather than the blocking one — it is the first platform that
implements `MetricsProvider` without `LiveTrackable`, which is the capability
split of § 12.1 finally being tested. The TikTok app and sandbox should be
created in parallel, because doing so also settles the two unknowns above.

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

### 12.1. This abstraction is unproven, and that is worth writing down

`MetricsProvider` and `LiveTrackable` currently have **exactly one
implementation** between them: `TwitchProvider`. That is textbook speculative
generality — a seam shaped for platforms that do not exist yet.

It is kept because the cost is small (about sixty lines of interface) and the
decision was made deliberately rather than by reflex. But an abstraction with
one implementation is a *guess about the future*, not a proven design, and
recording it as though it were planning would be dishonest. Only the second
provider will show whether the shape was right.

If YouTube turns out not to fit it, the correct response is to reshape the
interface, not to contort YouTube to match it.

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

---

## 14. A missing field is not a zero

**Decision:** every field the application depends on must be present and of the
right type. An absent or wrongly typed one is a broken response — recorded as a
`PARSE` failure, shown as an error, never substituted with a default.

**Why:** Jackson's `path()` returns an empty node for a field that is not there,
and `asLong()` on an empty node returns **zero** without complaint. A reply that
arrived with HTTP 200 but no `total` field was therefore read as "0 followers",
written into permanent history, and displayed with a green OK badge — and the
upsert then overwrote the correct value for that day.

This project's whole subject is refusing to show numbers nobody measured, and
the defect was sitting in the one layer nobody had audited: the parser. Two more
instances of the same thing were found alongside it. A missing `expires_in` read
as zero would mark a fresh token dead on arrival and loop the refresh logic. A
missing `data` array in `/streams` was read as "offline", which would end a
broadcast that was still running on the strength of a malformed reply.

**The distinction that matters:** a stream genuinely watched by nobody records a
real zero. `0` and "no answer" are different facts and the code now keeps them
apart, with tests that fail if anyone merges them again.

**Error messages** name the fields that were present but never quote their
values. Some of these responses carry access tokens, and exception text reaches
the logs.

---

## 15. When a live session is in doubt, ask — do not infer

**Decision:** the startup routine does not decide whether a broadcast has ended
by looking at the clock. It asks the platform whether the stream is still on
air. If that question cannot be answered, nothing is closed.

**Why:** a session can look abandoned simply because the application was closed
for a while. Marking it finished and then continuing to append samples left a
running broadcast recorded as over, with a summary frozen at the wrong moment.

The asymmetry decides the default: **leaving a session open is recoverable** —
the sampler sorts it out on the next poll. **Closing a running one is not** — its
duration and summary are wrong permanently. Where the two errors are not equally
costly, the cheap one is the correct guess.

A session closed early is reopened rather than duplicated, and its stored summary
is cleared. Peak and average computed at a premature close describe only part of
a broadcast while looking like final figures, which is worse than having none.

---

## 16. Monthly totals: provenance and coverage

**Decision:** the platform card carries a closed calendar month of totals, and
distinguishes two kinds of number that were previously shown as equals.

**Provenance.** Streams, hours on air, and follower and subscriber growth come
from values Twitch itself reported — exact. Hours watched, average and peak are
reconstructed from our own sampling — estimates, marked with a tilde. Growth over
a period is the one figure in the summary that needs no caveat at all: it is the
difference between two numbers Twitch gave us.

**Coverage.** Samples actually taken against samples a complete recording would
hold, shown as a pill with thresholds stated in the open (95% full, 70% partial).
Deliberately not a single "confidence score" — one number that blends unrelated
things sounds precise and cannot be interrogated.

**What coverage does not claim.** It reports whether *our* sampling was complete.
It says nothing about whether the numbers Twitch handed us were correct: a stream
sampled perfectly from start to finish reads as full coverage even if every count
was wrong at the source. We can be transparent about our own work; auditing
theirs needs the VOD (§ 18).

**A partial month says so.** When collection began after the month did, the
summary names the date it started rather than crediting a whole month with four
days of growth.

**Merged duration is the sum of time on air**, not the span from first start to
last end. Two broadcasts of one and four hours on the same day are five hours,
not the seven that includes the break between them. The weighting falls out
naturally, since no samples exist during a pause.

---

## 17. Merging broadcasts is never automatic

**Status: implemented.** Verified against a real broadcast that dropped and
resumed: two sessions recorded separately, one suggestion raised, merged by hand,
and the combined duration came out as the sum of both parts rather than the span
including the pause between them.

**Decision:** the application never merges two broadcasts on its own. It records
them separately, notices when they might belong together, and waits.

**Why:** the previous design merged automatically when a new stream id appeared
within ten minutes. That guesses intent from timing, and timing does not carry
intent — a connection dropping and a streamer deliberately ending one broadcast
to start another look identical through the API. Only the person knows which
happened.

The direction of the default follows § 15's asymmetry. **Merging wrongly is
invisible** — the numbers are simply wrong. **Splitting wrongly is obvious** —
one evening appears as three short streams. Between a silent error and a loud
one, this project takes the loud one every time.

**The interface constraint is part of the decision, not a detail.** A pending
merge lives in a permanent section of the dashboard until it is decided. Never a
popup: someone mid-broadcast cannot stop to think about whether two sessions are
one, and a prompt that appears and disappears forces the decision at the worst
possible moment.

**Shape:** one session per Twitch stream id, grouped by a nullable
`merged_into_id`. Reversible, because a merge that cannot be undone is another
irreversible guess. Combined figures survive sample pruning, since averages are
weighted by the stored `sample_count`.

**Nothing is offered while a broadcast is still running.** The first version got
this wrong in an obvious way: the same reasoning that rules out a pop-up rules
out asking mid-stream, and the card appeared the moment a second broadcast
began — which is to say, while the person was working.

There is a second reason beyond timing. A running broadcast has no duration, no
average and no peak yet, so the decision would be made about figures that do not
exist. The suggestion is still recorded when it is noticed; it simply waits until
the evening can be judged whole.

Enforced on the server as well as hidden in the interface. A rule that matters is
a rule the server keeps.

**A bad night arrives as one question.** Three drops produce three pairs, and
presenting those as three separate cards turns one judgement into a form. The
pairs are joined into the run of broadcasts they describe, shown in order with
the silence between each, and answered once — with the unwanted ones unticked.

---

## 18. Data settles before it is final

**Status: implemented.** The state machine has run; the archive comparison is
described in § 18.2.

**Decision:** a broadcast's figures are not treated as final the moment it ends.
Sessions carry a state — `LIVE`, `SETTLING`, `FINAL` — and numbers still settling
are shown marked as provisional.

**Why:** Twitch keeps reporting a stream as live for some minutes after it
actually ends, so the last samples of a session can be ghosts. Rather than
guessing which trailing samples are real, the interface says the figures are not
settled yet. This is the freshness rule applied to history: the uncertainty is
labelled rather than hidden.

**The VOD is the ground truth.** `/videos type=archive` carries the broadcast's
real duration according to Twitch itself. When it appears, it corrects the end
time and the ghost tail is discarded. Which method produced a session's end time
is recorded, so every figure can be traced to how it was obtained.

Two separate windows, previously conflated in one constant: a **grace** window
answers "is this the same broadcast?", a **settle** window answers "have the
numbers stopped moving?". They are not the same length and should never have
shared a number.

### 18.3. Three windows, and why none of them may share a number

A third turned up the same way, and the lesson repeated: constants that answer
different questions must not be tied together, however similar their values look
on the day they are written.

| window | question it answers | value | where it comes from |
|---|---|---|---|
| **Grace** | how long might Twitch still resume this same broadcast? | 2 min | Twitch tolerates 90 seconds of lost connection |
| **Suggest** | how far apart can two broadcasts be and still be one evening? | 10 min | the streamer's judgement |
| **Settle** | have the numbers stopped moving? | 20 min | how long an archive takes to appear |

Grace was ten minutes, from when it decided whether an evening stayed in one
piece. It no longer decides that: merging became a person's choice, and a
broadcast returning under the same id is reopened whether or not its session had
closed. Closing early now costs a row that briefly reads as finished; what the
long wait cost was ten minutes of watching a dashboard after every broadcast.

**Grace and suggest had been written as the same constant.** Shortening one would
have silently narrowed the other, and broadcasts five minutes apart would have
stopped being offered for merging at all — a change nobody asked for, arriving
as a side effect, discovered only when a night went unmerged. They are separate
constants now because they measure separate things.

---

---

## 18.1. The collector polls once a minute, on air or not

**Decision:** one poll per minute, always. No slower cadence while off air.

**Why the change:** it used to drop to five minutes between broadcasts, to be
polite about the API. That politeness cost up to five minutes of every
broadcast — a stream starting just after a poll goes unsampled until the next
one, and **nobody can measure the past**. No platform keeps per-minute viewer
counts for anyone to query later, which is the entire reason this application
exists. Those minutes are gone for good.

And the saving was imaginary. Twitch's limit is per minute, not per day, so one
call a minute spends roughly a tenth of one percent of it. A real cost in data
quality had been traded for a rounding error.

**Accepted trade-off:** `collection_run` grows to around 1,400 rows a day rather
than a few hundred — a few megabytes before the ninety-day prune, which is more
than the rest of the database put together and still nothing in absolute terms.

**What this does not fix:** the gap is now at most one minute rather than five,
never zero. A broadcast that starts between two polls is still unmeasured until
the next one, and sampling once a minute cannot see a spike that lasts forty
seconds. Both are reported rather than hidden: coverage counts samples taken
against the broadcast's real length, so whatever is missed shows up as a number
instead of being quietly rounded away.

**Also removed:** the adaptive cadence took the live/off-air state with it. Once
the interval stopped depending on it, that state had no reader left.

---

## 18.2. Peak is the figure sampling damages most

**Measured against Twitch's own dashboard**, on a four-hour broadcast sampled
once a minute:

| | Twitch | here |
|---|---|---|
| Average viewers | 2 | 2.29 |
| Peak viewers | 6 | 5 |
| Duration | 4h03m | 4h01m |

**The average is essentially exact and the peak is not**, and the reason is that
they are different kinds of number. An average spreads its error across every
sample, so more samples make it better. A peak depends on a single instant: if
that instant falls between two samples it is gone, and no nearby sample makes up
for it. Sampling can only ever push a peak downwards, never up.

**This gets worse as an audience grows.** A raid or a clip going around produces
a brief spike, and brief spikes are exactly what one-minute sampling misses. On a
channel with a handful of viewers the gap is one person. On a larger one it could
be hundreds.

**Not fixed, deliberately.** Sampling every fifteen seconds would catch most
spikes and cost about half a percent of the rate limit. But two stored figures
assume one sample equals one minute — hours watched is `average × sample count`,
and coverage compares samples against the broadcast's length in minutes. Both
would silently produce numbers four times too large.

Doing it properly means storing the sampling interval and changing both
calculations. That is worth doing when the gap starts to matter; it is not worth
doing to correct a difference of one viewer, on top of untested changes, the
evening before a test.

**What was done instead:** the label now reads *Peak observed* rather than
*Peak*. It corrects nothing, and it stops presenting an estimate as a
measurement.

**Also learned from the same comparison:** the assumption behind § 18 — that
Twitch keeps reporting a stream as live after it ends, leaving a ghost tail — did
not hold here. The stream vanished from the listing about two minutes *before*
Twitch's own dashboard says it ended, so the recorded duration came out short
rather than long. The archive correction still applies; it extends the end rather
than trimming it. The ghost tail remains a possibility, not an observation.

---

## 19. What has actually been tested

**Decision:** state the range the collector has been validated over, rather than
leaving it open and implying any duration works.

**Validated:** broadcasts up to **four hours**, which is the length actually
streamed. Testing twelve hours for sport would exercise a case that does not
exist, while leaving the real one unverified.

**Why four hours is a real boundary and not an arbitrary one:** a Twitch access
token lives roughly four hours. A session of that length exercises the refresh
path once. A longer one would exercise it twice, and nothing has ever run that
far. The limit of what has been tested happens to sit exactly where a code path
changes behaviour, so it is worth naming.

**Measured during that run:** around 100 MB of memory and effectively no CPU
between polls, with OBS encoding and transmitting on the same machine. That is
the first verification of the constraint in § 1 — the one requirement that
predates every other decision — under the conditions it was written for.

**What this does not claim:** that longer broadcasts fail. Only that nobody has
watched one, and this project does not report things nobody watched.

---

## 19.1. The tests are proven by breaking the code

**Decision:** cover the two places this project has already got wrong — the
stored summary of a broadcast, and the collector's decisions when a stream goes
quiet — with automated tests, and prove each test by deliberately breaking the
code it guards.

**Why these two and not everything:** both bugs that reached real history came
from here, and both were found the same way: by watching a live broadcast be
recorded incorrectly. Neither threw anything. A duration that silently includes
a forty-minute disconnection is still a number of hours, and once the samples
are pruned at ninety days (§ 6.1) the stored summary is the only surviving
record. Wrong permanently, and plausible the whole time.

**Proven, not assumed.** A test that has never failed has never been shown to
work. Each of these was checked by sabotaging the code underneath it:

| Sabotage | Test that caught it | Reported |
|---|---|---|
| Duration measured start-to-end instead of time on air | `mergeExcludesTheGap` | expected 5400, was 7200 |
| Average of averages instead of weighted mean | `averageIsWeightedBySampleCount` | expected 12.9, was 54.8 |
| Bound on `sampled_at <= ended_at` removed | `ghostTailIsExcluded` | expected 5, was 999 |
| A returning broadcast opens a second session | `sameStreamIdReopensTheSession` | no session on air |
| Unreachable platform treated as "off air" | `unreachableTwitchClosesNothing` | session closed |

Each sabotage broke exactly one test, which is the part worth noting: the suite
says *which* thing is wrong, not merely that something is.

**A temporary file, not `:memory:`.** In-memory reads better and would not have
worked: every connection to `:memory:` gets its own empty database, so Flyway
would migrate one and the code under test would read another. The temporary file
also keeps WAL and foreign keys exactly as in production. JUnit deletes it.

**Not covered:** the HTTP layer (`MergeRoutes` ownership checks are exercised
through the repository they rely on, not through Javalin), `SettleJob`,
`FreshnessCache`, `RateLimitGate` and `ClockSkew`. Named here rather than left
implied, on the same principle as § 16: an untested area that nobody has written
down is indistinguishable from one nobody thought about.

---

## Parked

Twitch is frozen. What follows is written down so it stops taking up room, not
because anyone is working on it. Nothing here gets built until the other
platforms are in, unless something breaks.

- [ ] **Seeing a merged evening both ways.** A merged group currently shows only
  as one line. Sometimes the question is "how did Tuesday go", and sometimes it
  is "how bad was that outage" — the second needs the parts visible. The data
  supports both already, since merging is a link and nothing was destroyed to
  make it; this is a display that does not exist yet.
- [ ] **Follower count at the start and end of a broadcast.** Twitch reports
  "+1 follower" for a stream and this application cannot, because snapshots are
  daily and one value per day overwrites the rest. Two extra readings per
  broadcast would answer it. Surfaced when comparing against their dashboard on
  2026-09-01.
- [ ] **Automatic backups.** Decided on day one (§ 8), still not built. The
  database is the only irreplaceable thing here.
- [ ] **Charts over the collected history.** The reason the snapshots exist.
- [ ] **Sampling faster to catch brief peaks** (§ 18.2), which requires storing
  the sampling interval and changing two calculations.

## Open questions

- [ ] **Per-post data.** No table yet. It is what would most help decide what to
  post, and also what grows fastest in volume. The next thing to design after
  the MVP.
- [ ] **Goals.** No table for targets (e.g. Twitch Partner requirements). Small
  table, but it changes the layout of the landing screen — decide before
  designing the UI.
