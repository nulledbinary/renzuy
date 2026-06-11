package renzuy.counting;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import renzuy.store.StateStore;

/**
 * Per-guild counting-game state: which channel hosts the game, the current
 * count, and who counted last (to enforce the no-double-counting rule).
 *
 * <p>State is write-through: every mutation lands in the shared
 * {@link StateStore} (DynamoDB in production), so the run survives both bot
 * restarts and container replacement. Reads are served from an in-memory
 * cache — the backend is only hit once per guild, not once per message.
 */
public final class CountingStore {

    /** Immutable snapshot of one guild's game. {@code lastUserId} is 0 after a reset. */
    public record State(long channelId, long count, long lastUserId) {}

    private static final String SORT_KEY = "counting";

    private final StateStore store;
    /** Guild ID → cached state; empty Optional caches "no game bound". */
    private final Map<Long, Optional<State>> cache = new ConcurrentHashMap<>();

    public CountingStore(StateStore store) {
        this.store = store;
    }

    /** @return the guild's game state, or {@code null} when no channel is bound. */
    public State get(long guildId) {
        return cache.computeIfAbsent(guildId, this::loadState).orElse(null);
    }

    /**
     * Binds the game to a channel. Re-binding the same channel keeps the run
     * going; moving to a different channel starts a fresh run from 0.
     *
     * @return the previous state, or {@code null} if the game was not set up yet
     */
    public State bind(long guildId, long channelId) {
        State previous = get(guildId);
        if (previous == null || previous.channelId() != channelId) {
            write(guildId, new State(channelId, 0, 0));
        }
        return previous;
    }

    /** Records a correct count. */
    public void advance(long guildId, long count, long userId) {
        State state = get(guildId);
        if (state == null) return;
        write(guildId, new State(state.channelId(), count, userId));
    }

    /** Resets the run to 0 (wrong number, broken rule). The channel stays bound. */
    public void reset(long guildId) {
        State state = get(guildId);
        if (state == null) return;
        write(guildId, new State(state.channelId(), 0, 0));
    }

    private Optional<State> loadState(long guildId) {
        return store.get(pk(guildId), SORT_KEY).flatMap(CountingStore::parse);
    }

    private void write(long guildId, State state) {
        cache.put(guildId, Optional.of(state));
        store.put(pk(guildId), SORT_KEY, Map.of(
                "channelId", Long.toString(state.channelId()),
                "count", Long.toString(state.count()),
                "lastUserId", Long.toString(state.lastUserId())));
    }

    private static Optional<State> parse(Map<String, String> attrs) {
        try {
            return Optional.of(new State(
                    Long.parseLong(attrs.getOrDefault("channelId", "")),
                    Long.parseLong(attrs.getOrDefault("count", "0")),
                    Long.parseLong(attrs.getOrDefault("lastUserId", "0"))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String pk(long guildId) {
        return "guild#" + guildId;
    }
}
