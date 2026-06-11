package renzuy.store;

import java.util.Map;
import java.util.Optional;

/**
 * Tiny key-value persistence facade shared by every feature that must survive
 * restarts (counting game, log-channel bindings, confession audit records,
 * channel lockdowns).
 *
 * <p>Items are addressed by a partition key + sort key pair and hold a flat
 * {@code String → String} attribute map. The production backend is DynamoDB
 * ({@link DynamoStateStore}); local development falls back to a flat file
 * ({@link FileStateStore}). Pick one via {@link StateStores#fromEnv()}.
 *
 * <p>Key conventions used across the bot:
 * <pre>
 *   guild#&lt;guildId&gt;        counting              the counting-game run
 *   guild#&lt;guildId&gt;        bind#&lt;category&gt;       log category → channel binding
 *   guild#&lt;guildId&gt;        confess-block#&lt;userId&gt; user restricted from /confess
 *   guild#&lt;guildId&gt;        lockdown#&lt;channelId&gt;  saved overrides of a locked channel
 *   confession#&lt;messageId&gt; meta                  who posted which confession
 * </pre>
 */
public interface StateStore {

    /** @return the item's attributes, or empty when the item does not exist */
    Optional<Map<String, String>> get(String pk, String sk);

    /** @return every item under the partition key, keyed by sort key (may be empty) */
    Map<String, Map<String, String>> query(String pk);

    /** Creates or fully replaces an item. */
    void put(String pk, String sk, Map<String, String> attributes);

    /** Deletes an item; no-op when it does not exist. */
    void delete(String pk, String sk);
}
