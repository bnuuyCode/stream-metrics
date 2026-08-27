package io.github.bnuuycode.streammetrics;

import io.github.bnuuycode.streammetrics.collector.Collector;
import io.github.bnuuycode.streammetrics.collector.LiveSampler;
import io.github.bnuuycode.streammetrics.collector.SnapshotJob;
import io.github.bnuuycode.streammetrics.config.AppConfig;
import io.github.bnuuycode.streammetrics.db.AccountRepository;
import io.github.bnuuycode.streammetrics.db.CollectionLog;
import io.github.bnuuycode.streammetrics.db.Database;
import io.github.bnuuycode.streammetrics.db.SnapshotRepository;
import io.github.bnuuycode.streammetrics.db.StreamRepository;
import io.github.bnuuycode.streammetrics.metrics.MetricsProvider;
import io.github.bnuuycode.streammetrics.twitch.TwitchLiveService;
import io.github.bnuuycode.streammetrics.twitch.TwitchMetricsService;
import io.github.bnuuycode.streammetrics.twitch.TwitchProvider;
import io.github.bnuuycode.streammetrics.twitch.TwitchSession;
import io.github.bnuuycode.streammetrics.web.ApiResponse;
import io.github.bnuuycode.streammetrics.web.AuthRoutes;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Entry point and wiring.
 *
 * <p>Startup order matters: configuration, then database, then providers, then
 * the web server, then the background collector. If the schema cannot be
 * brought up to date the application must die right here, before accepting a
 * single request. A server that answers while its database is broken is a
 * server that lies.
 */
public final class Main {

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        Database database = Database.initialize(config);

        AccountRepository accounts = new AccountRepository(database.jdbi());
        SnapshotRepository snapshots = new SnapshotRepository(database.jdbi());
        StreamRepository streams = new StreamRepository(database.jdbi());
        CollectionLog runs = new CollectionLog(database.jdbi());

        // ------------------------------------------------------------------
        // Platform registry.
        //
        // This is the seam. Adding YouTube means writing YouTubeProvider and
        // adding one line here — no existing file changes. That is all the
        // "plugin system" this project needs (DECISIONS.md § 12).
        // ------------------------------------------------------------------
        List<MetricsProvider> providers = new ArrayList<>();
        List<LiveSampler.Tracked> trackable = new ArrayList<>();

        Optional<TwitchSession> twitchSession = config.twitch()
                .map(twitch -> new TwitchSession(twitch, accounts));

        twitchSession.ifPresent(session -> {
            TwitchProvider provider = new TwitchProvider(session);
            providers.add(provider);
            // Twitch broadcasts, so it goes in both lists.
            trackable.add(new LiveSampler.Tracked(provider, provider));
        });

        Optional<TwitchMetricsService> twitchMetrics = twitchSession.map(TwitchMetricsService::new);
        Optional<TwitchLiveService> twitchLive = twitchSession
                .map(session -> new TwitchLiveService(session, streams));

        // ------------------------------------------------------------------
        // Web
        // ------------------------------------------------------------------
        Javalin app = Javalin.create(javalin -> {
            // Serves src/main/resources/public as the dashboard. One process,
            // one port: no separate front-end server to remember to start.
            javalin.staticFiles.add("/public", Location.CLASSPATH);
        });

        // Health check. Note it already travels inside ApiResponse: from the
        // very first endpoint, nothing leaves this application without a
        // timestamp attached.
        app.get("/api/health", ctx ->
                ctx.json(ApiResponse.ok(database.status(), Instant.now())));

        // The live path: current numbers, fetched on demand (DECISIONS.md § 4).
        app.get("/api/twitch/metrics", ctx -> ctx.json(
                twitchMetrics
                        .map(TwitchMetricsService::current)
                        .orElseGet(() -> TwitchMetricsService.Metrics.unavailable(
                                "Twitch is not configured", Instant.now()))));

        // On air right now, plus the running totals of the current session.
        app.get("/api/twitch/live", ctx -> ctx.json(
                twitchLive
                        .map(TwitchLiveService::current)
                        .orElseGet(() -> ApiResponse.error("Twitch is not configured", Instant.now()))));

        // The historical path: past broadcasts, read from our own database.
        // A local read over a small table — the limit is about what fits on
        // screen, not about cost.
        app.get("/api/twitch/sessions", ctx -> {
            int limit = Math.min(50, Math.max(1, ctx.queryParamAsClass("limit", Integer.class).getOrDefault(5)));
            ctx.json(twitchLive
                    .map(live -> live.recent(limit))
                    .orElseGet(() -> ApiResponse.error("Twitch is not configured", Instant.now())));
        });

        new AuthRoutes(config, accounts).register(app);

        app.start(config.port());

        // ------------------------------------------------------------------
        // Background collection — started after the server, so a slow first
        // API call never delays the dashboard becoming reachable.
        // ------------------------------------------------------------------
        Collector collector = new Collector(
                new SnapshotJob(providers, snapshots, runs, config.zone()),
                new LiveSampler(trackable, streams, runs));

        collector.start();

        // Closes live sessions cleanly on Ctrl+C instead of leaving one open
        // for the next run to clean up.
        Runtime.getRuntime().addShutdownHook(new Thread(collector::stop));

        // Printed after start() so it lands at the bottom of the console,
        // once the server is genuinely accepting requests.
        Banner.print(config.port());
    }
}
