package io.github.bnuuycode.streammetrics.web;

import io.github.bnuuycode.streammetrics.db.AccountRepository;
import io.github.bnuuycode.streammetrics.db.AccountRepository.StoredAccount;
import io.github.bnuuycode.streammetrics.db.MergeSuggestionRepository;
import io.github.bnuuycode.streammetrics.db.MergeSuggestionRepository.PendingSuggestion;
import io.github.bnuuycode.streammetrics.db.StreamRepository;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Answering the collector's question about whether several broadcasts were one.
 *
 * <p>Nothing here happens on a timer. The question sits in the database until a
 * person acts on it, which is the entire point: someone mid-broadcast cannot
 * decide this, and a decision forced at the wrong moment is worse than one
 * postponed (DECISIONS.md § 17).
 */
public final class MergeRoutes {

    private static final Logger log = LoggerFactory.getLogger(MergeRoutes.class);

    private static final String PLATFORM = "twitch";

    private final AccountRepository accounts;
    private final StreamRepository streams;
    private final MergeSuggestionRepository suggestions;

    public MergeRoutes(AccountRepository accounts,
                       StreamRepository streams,
                       MergeSuggestionRepository suggestions) {
        this.accounts = accounts;
        this.streams = streams;
        this.suggestions = suggestions;
    }

    public void register(Javalin app) {
        app.get("/api/twitch/merges", this::listPending);
        app.post("/api/twitch/merges/apply", this::applySelection);
        app.post("/api/twitch/merges/dismiss", this::dismissCluster);
        app.post("/api/twitch/sessions/{id}/unmerge", this::unmerge);
    }

    /**
     * The open questions, grouped into the evenings they describe.
     *
     * <p>A bad night arrives as several pairs — dropped, resumed, dropped,
     * resumed. Presented as separate questions that becomes a form. Presented as
     * one run of broadcasts in order, with the silence between each, it is the
     * single judgement it actually is.
     */
    private void listPending(Context ctx) {
        Instant now = Instant.now();

        ctx.json(accounts.findAccount(PLATFORM)
                .map(account -> ApiResponse.ok(cluster(suggestions.pending(account.id())), now))
                .orElseGet(() -> ApiResponse.error("No Twitch account connected", now)));
    }

    /**
     * Merges exactly the broadcasts that were ticked.
     *
     * <p>Takes a set rather than answering one junction at a time, so the case
     * that matters stays expressible: dropped twice and resumed, then genuinely
     * ended and started something else. Ticking the first three and leaving the
     * fourth alone says that in one action.
     *
     * <p>Afterwards, any pending question whose two broadcasts ended up in the
     * same group has been answered by that fact and is closed. Questions the
     * selection did not settle stay open, because nobody decided them.
     */
    private void applySelection(Context ctx) {
        Instant now = Instant.now();

        Optional<Long> accountId = accounts.findAccount(PLATFORM).map(StoredAccount::id);
        if (accountId.isEmpty()) {
            ctx.status(409).json(ApiResponse.error("No Twitch account connected", now));
            return;
        }

        Selection selection = ctx.bodyAsClass(Selection.class);
        List<Long> ids = selection == null || selection.sessionIds() == null
                ? List.of()
                : selection.sessionIds();

        if (ids.size() < 2) {
            ctx.status(400).json(ApiResponse.error("Pick at least two broadcasts to merge", now));
            return;
        }

        // Refused while any of them is still running, and checked here rather
        // than only in the interface: a rule that matters is a rule the server
        // enforces. The figures of a live broadcast do not exist yet, so this
        // would be a decision made without the thing being decided about.
        if (streams.anyStillLive(ids)) {
            ctx.status(409).json(ApiResponse.error(
                    "One of these broadcasts is still on air. It can be merged once it ends.", now));
            return;
        }

        streams.mergeAll(ids);
        int settled = closeSettledQuestions(accountId.get());

        log.info("Merged {} broadcasts by selection; {} question(s) settled", ids.size(), settled);
        ctx.json(ApiResponse.ok("MERGED", now));
    }

    /**
     * Marks a run of broadcasts as genuinely separate.
     *
     * <p>Recorded rather than deleted. "I already said these are separate" is
     * worth remembering, or the same question returns forever.
     */
    private void dismissCluster(Context ctx) {
        Instant now = Instant.now();

        Optional<Long> accountId = accounts.findAccount(PLATFORM).map(StoredAccount::id);
        if (accountId.isEmpty()) {
            ctx.status(409).json(ApiResponse.error("No Twitch account connected", now));
            return;
        }

        Selection selection = ctx.bodyAsClass(Selection.class);
        List<Long> requested = selection == null || selection.suggestionIds() == null
                ? List.of()
                : selection.suggestionIds();

        // Only ids that are genuinely this account's open questions. The sibling
        // endpoint already checked ownership and this one did not, which is the
        // kind of asymmetry that is harmless in a single-user application right
        // up until it is not.
        Set<Long> mine = suggestions.pending(accountId.get()).stream()
                .map(PendingSuggestion::id)
                .collect(Collectors.toSet());

        List<Long> allowed = requested.stream().filter(mine::contains).toList();
        allowed.forEach(id -> suggestions.decide(id, MergeSuggestionRepository.DISMISSED));

        log.info("{} question(s) answered as separate broadcasts", allowed.size());
        ctx.json(ApiResponse.ok("DISMISSED", now));
    }

    /**
     * Detaches a broadcast from the group it was merged into.
     *
     * <p>Possible because a merge is a link and never a rewrite. Nothing was
     * destroyed to create it, so undoing it costs one update and loses nothing.
     */
    private void unmerge(Context ctx) {
        Instant now = Instant.now();
        long sessionId = Long.parseLong(ctx.pathParam("id"));

        Optional<Long> accountId = accounts.findAccount(PLATFORM).map(StoredAccount::id);
        if (accountId.isEmpty() || !streams.belongsTo(sessionId, accountId.get())) {
            ctx.status(404).json(ApiResponse.error("No such broadcast", now));
            return;
        }

        streams.unmerge(sessionId);
        log.info("Session {} detached from its group", sessionId);
        ctx.json(ApiResponse.ok("UNMERGED", now));
    }

    private int closeSettledQuestions(long accountId) {
        int settled = 0;

        for (PendingSuggestion pending : suggestions.pending(accountId)) {
            if (streams.groupHead(pending.sessionId()) == streams.groupHead(pending.intoSessionId())) {
                suggestions.decide(pending.id(), MergeSuggestionRepository.MERGED);
                settled++;
            }
        }

        return settled;
    }

    // -----------------------------------------------------------------------
    // Turning pairs into evenings
    //
    // Each suggestion links two broadcasts. Several consecutive drops produce a
    // chain of those links, and the chain is the story worth showing. Connected
    // components of that graph are the evenings.
    // -----------------------------------------------------------------------

    private static List<Cluster> cluster(List<PendingSuggestion> pending) {
        Map<Long, Set<Long>> neighbours = new HashMap<>();
        Map<Long, Broadcast> broadcasts = new HashMap<>();
        Map<Long, List<Long>> suggestionsBySession = new HashMap<>();

        for (PendingSuggestion s : pending) {
            neighbours.computeIfAbsent(s.sessionId(), k -> new HashSet<>()).add(s.intoSessionId());
            neighbours.computeIfAbsent(s.intoSessionId(), k -> new HashSet<>()).add(s.sessionId());

            broadcasts.putIfAbsent(s.intoSessionId(),
                    new Broadcast(s.intoSessionId(), s.intoTitle(), s.intoStartedAt(), s.intoEndedAt()));
            broadcasts.putIfAbsent(s.sessionId(),
                    new Broadcast(s.sessionId(), s.title(), s.startedAt(), s.endedAt()));

            suggestionsBySession.computeIfAbsent(s.sessionId(), k -> new ArrayList<>()).add(s.id());
            suggestionsBySession.computeIfAbsent(s.intoSessionId(), k -> new ArrayList<>()).add(s.id());
        }

        List<Cluster> clusters = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (Long start : neighbours.keySet()) {
            if (!seen.add(start)) {
                continue;
            }

            List<Long> members = new ArrayList<>();
            Deque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            members.add(start);

            while (!queue.isEmpty()) {
                for (Long next : neighbours.getOrDefault(queue.poll(), Set.of())) {
                    if (seen.add(next)) {
                        members.add(next);
                        queue.add(next);
                    }
                }
            }

            // Not offered while any part of it is still on air.
            //
            // The same reasoning that rules out a pop-up rules this out. Someone
            // mid-broadcast cannot weigh whether two sessions are one, and here
            // they would also be deciding without the whole picture: the running
            // broadcast has no duration, no average and no peak yet. A decision
            // taken then is a guess dressed as an answer.
            //
            // The suggestion is still recorded. It simply waits until the work
            // is over and the evening can be judged whole.
            if (members.stream().map(broadcasts::get).anyMatch(b -> b != null && b.endedAt() == null)) {
                continue;
            }

            Cluster cluster = build(members, broadcasts, suggestionsBySession);

            // An empty run cannot be shown or sorted — the sort below reads the
            // first part of every cluster. build() drops broadcasts with no start
            // time, so being defensive there and not here is what would turn a
            // row that should not exist into a crash.
            if (!cluster.parts().isEmpty()) {
                clusters.add(cluster);
            }
        }

        clusters.sort(Comparator.comparing((Cluster c) -> c.parts().get(0).startedAt()).reversed());
        return clusters;
    }

    private static Cluster build(List<Long> members,
                                 Map<Long, Broadcast> broadcasts,
                                 Map<Long, List<Long>> suggestionsBySession) {

        List<Broadcast> ordered = members.stream()
                .map(broadcasts::get)
                .filter(b -> b != null && b.startedAt() != null)
                .sorted(Comparator.comparing(Broadcast::startedAt))
                .toList();

        List<Part> parts = new ArrayList<>();
        Instant previousEnd = null;

        for (Broadcast b : ordered) {
            Long gap = previousEnd == null || b.startedAt() == null
                    ? null
                    : Duration.between(previousEnd, b.startedAt()).getSeconds();

            parts.add(new Part(
                    b.id(),
                    b.title(),
                    iso(b.startedAt()),
                    iso(b.endedAt()),
                    b.endedAt() == null ? null : Duration.between(b.startedAt(), b.endedAt()).getSeconds(),
                    gap));

            previousEnd = b.endedAt();
        }

        List<Long> suggestionIds = members.stream()
                .flatMap(id -> suggestionsBySession.getOrDefault(id, List.of()).stream())
                .distinct()
                .toList();

        return new Cluster(suggestionIds, parts);
    }

    private static String iso(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private record Broadcast(long id, String title, Instant startedAt, Instant endedAt) {
    }

    /**
     * One evening's worth of broadcasts the collector could not tell apart.
     *
     * @param suggestionIds every question this run covers, so dismissing the
     *                      whole thing answers all of them at once
     */
    public record Cluster(List<Long> suggestionIds, List<Part> parts) {
    }

    /**
     * @param gapBeforeSeconds silence since the previous broadcast, null for the
     *                         first. Shown raw rather than interpreted: the gap
     *                         is all the evidence there is, and the person
     *                         reading it knows what happened during those minutes
     * @param onAirSeconds     null while a broadcast is still running
     */
    public record Part(
            long sessionId,
            String title,
            String startedAt,
            String endedAt,
            Long onAirSeconds,
            Long gapBeforeSeconds) {
    }

    /**
     * Request body for both actions.
     *
     * @param sessionIds    which broadcasts to merge
     * @param suggestionIds which questions to mark as answered "separate"
     */
    public record Selection(List<Long> sessionIds, List<Long> suggestionIds) {
    }
}
