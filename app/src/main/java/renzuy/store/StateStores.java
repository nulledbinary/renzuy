package renzuy.store;

import renzuy.DotEnv;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Picks the {@link StateStore} backend for this process.
 *
 * <p>{@code RENZUY_DDB_TABLE} set (the ECS task definition sets it to
 * {@code renzuy-bot-state}) → DynamoDB, verified with a probe read so a
 * misconfigured table/role fails loudly at startup instead of silently
 * dropping every write. Unset → flat file under {@code RENZUY_CONFIG_DIR},
 * which is the no-credentials local-dev path.
 */
public final class StateStores {

    private StateStores() {}

    public static StateStore fromEnv() {
        String table = DotEnv.get("RENZUY_DDB_TABLE");
        if (table == null || table.isBlank()) {
            System.out.println("[StateStore] RENZUY_DDB_TABLE not set — using file-backed store");
            return FileStateStore.defaultLocation();
        }
        try {
            DynamoDbClient client = DynamoDbClient.builder().build();
            DynamoStateStore store = new DynamoStateStore(client, table);
            store.get("startup", "probe");
            System.out.println("[StateStore] Using DynamoDB table " + table);
            return store;
        } catch (Exception e) {
            System.err.println("[StateStore] DynamoDB unavailable (" + e.getMessage()
                    + ") — falling back to file-backed store. Persistence will NOT survive "
                    + "container replacement; fix the table/role configuration.");
            return FileStateStore.defaultLocation();
        }
    }
}
