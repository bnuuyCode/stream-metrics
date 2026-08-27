# stream-metrics

A local social media metrics aggregator for streamers. One screen, every
platform, and a history the platforms do not keep for you.

Runs entirely on your own machine. No account, no subscription, no data leaving
the building.

> **Status: 0.1, early.** Twitch works end to end — login, live view, current
> totals, daily history and per-stream statistics. Charts, automatic backups and
> every other platform do not exist yet. Built in the open; expect the shape of
> things to keep moving.

---

## Why

Creator metrics live on six or seven different dashboards. Keeping up with them
means opening six or seven tabs, every day. Tools that unify this exist and are
expensive.

There is a second problem those tools charge for, and it is the more interesting
one: **the platforms show you today, not last month.** Twitch's own dashboard is
genuinely good, but its window is fixed, its format is its own, and there is no
way to put Twitch and YouTube on the same axis. Some numbers — average viewers,
peak viewers, how long a broadcast actually ran — are not in the API at all.
They exist only if something writes them down, minute by minute.

That is what this does.

## The one rule

> **No number reaches the screen without saying how fresh it is.**

A dashboard that shows an old number without saying so is worse than no
dashboard, because it produces confident wrong decisions. So every value in this
application travels inside an envelope:

```json
{ "value": 1234, "fetchedAt": "2026-08-27T14:32:11Z", "status": "OK" }
```

`status` is `OK`, `STALE` or `ERROR`, and the UI renders all three differently —
a stale number is dimmed and timestamped, an error shows no number at all.

The rule is enforced by the type, not by discipline: `ApiResponse.error()` has no
overload that accepts a value, so an error is structurally incapable of carrying
a stale figure onto the screen. A test guards it.

The same principle runs through the storage layer. A day with no data has **no
row** — never an interpolated one. Every collection attempt is logged, including
the failures, so a gap in a chart can be explained rather than merely noticed.

## What it does today

- **Live view** — on air status, current viewers, peak so far, uptime
- **Current totals** — followers and subscribers, fetched on demand
- **Daily history** — a snapshot per metric per day, kept forever
- **Broadcast history** — duration, peak and average viewers per stream, built
  from samples taken every minute while live

Twitch is implemented. The collector is platform-agnostic: adding another means
writing one class and one line of registration.

## Stack

Java 17 · [Javalin](https://javalin.io) · SQLite · [JDBI](https://jdbi.org) ·
[Flyway](https://flywaydb.org) · Maven

Front end is plain HTML, CSS and JavaScript served by the same process. No npm,
no bundler, no build step — the whole point is to stay light.

Deliberately not Spring: a thin layer over Jetty starts in about a second and
the request flow is readable end to end. The reasoning behind this and every
other choice is in [`docs/DECISIONS.md`](docs/DECISIONS.md).

## Running it

Requires Java 17 or newer.

```bash
mvn clean package
java -jar target/stream-metrics.jar
```

Then open <http://localhost:7000>.

It starts fine with no configuration at all and reports Twitch as *not
configured*. To connect a channel:

1. Register an application at <https://dev.twitch.tv/console/apps>
   - **OAuth Redirect URL:** `http://localhost:7000/auth/twitch/callback`
   - **Client Type:** Confidential
   - (Twitch requires two-factor authentication on the account to register one.)
2. Copy `config.properties.example` to `config.properties` and fill in the
   client id and secret.
3. Restart, open the dashboard, and click **Connect Twitch**.

`config.properties` is git-ignored. The application refuses to start if a
credential is found in the versioned example file — see
[`SecretGuard`](src/main/java/io/github/bnuuycode/streammetrics/config/SecretGuard.java).

### Scopes

`moderator:read:followers` and `channel:read:subscriptions`. Both require the
broadcaster to log in personally — an app token is not enough, since Twitch
removed the unauthenticated follower endpoint in 2023.

## Data

Everything lives in one SQLite file, `data/stream-metrics.db`.

It is small: roughly 1 MB per year of daily snapshots and 4 MB per year of
viewer samples. Ten years fit in about 50 MB. Minute-by-minute samples are
summarised into their session and deleted after ninety days; daily snapshots are
kept forever.

**That file is irreplaceable.** Platforms do not sell the past back — a lost
snapshot is lost for good. Back it up with
`VACUUM INTO 'backup.db'`, which is safe to run while the application is going.

## Licence

MIT.
