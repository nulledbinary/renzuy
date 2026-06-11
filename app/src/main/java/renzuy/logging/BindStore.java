package renzuy.logging;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import renzuy.store.StateStore;

/**
 * Persistent {@link LogCategory} → channel bindings, per guild.
 *
 * <p>Bindings are write-through to the shared {@link StateStore}
 * (sort key {@code bind#<slug>}), so a restarted or replaced container keeps
 * routing events without anyone re-running {@code /bind}. A guild's bindings
 * are loaded with a single query on first access and cached.
 */
public final class BindStore {

    private static final String SK_PREFIX = "bind#";

    private final StateStore store;
    private final Map<Long, Map<LogCategory, Long>> cache = new ConcurrentHashMap<>();

    public BindStore(StateStore store) {
        this.store = store;
    }

    /** @return the channel bound for this category, or {@code null} when unbound */
    public Long channelFor(long guildId, LogCategory category) {
        return bindings(guildId).get(category);
    }

    /** @return a snapshot of every binding the guild has */
    public Map<LogCategory, Long> all(long guildId) {
        return Map.copyOf(bindings(guildId));
    }

    /** @return the previously bound channel for the category, or {@code null} */
    public Long bind(long guildId, LogCategory category, long channelId) {
        Long previous = bindings(guildId).put(category, channelId);
        store.put(pk(guildId), SK_PREFIX + category.slug(),
                Map.of("channelId", Long.toString(channelId)));
        return previous;
    }

    /** @return true when the category was bound and is now removed */
    public boolean unbind(long guildId, LogCategory category) {
        Long previous = bindings(guildId).remove(category);
        store.delete(pk(guildId), SK_PREFIX + category.slug());
        return previous != null;
    }

    private Map<LogCategory, Long> bindings(long guildId) {
        return cache.computeIfAbsent(guildId, this::load);
    }

    private Map<LogCategory, Long> load(long guildId) {
        Map<LogCategory, Long> loaded = new EnumMap<>(LogCategory.class);
        for (Map.Entry<String, Map<String, String>> item : store.query(pk(guildId)).entrySet()) {
            if (!item.getKey().startsWith(SK_PREFIX)) continue;
            LogCategory category = LogCategory.fromSlug(item.getKey().substring(SK_PREFIX.length()))
                    .orElse(null);
            if (category == null) continue;
            try {
                loaded.put(category, Long.parseLong(item.getValue().getOrDefault("channelId", "")));
            } catch (NumberFormatException ignored) {
                // stale or malformed binding — skip
            }
        }
        // ConcurrentHashMap wrapper not needed: EnumMap mutations only happen
        // under computeIfAbsent's mapping or via put/remove on event threads,
        // so use a synchronized view to stay safe under JDA's thread pool.
        return java.util.Collections.synchronizedMap(loaded);
    }

    private static String pk(long guildId) {
        return "guild#" + guildId;
    }
}
