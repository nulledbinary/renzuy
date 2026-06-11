package renzuy.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LogCategoryTest {

    @Test
    void everySlugResolvesBackToItsCategory() {
        for (LogCategory category : LogCategory.values()) {
            assertEquals(category, LogCategory.fromSlug(category.slug()).orElseThrow());
        }
    }

    @Test
    void slugsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (LogCategory category : LogCategory.values()) {
            assertTrue(seen.add(category.slug()), "duplicate slug: " + category.slug());
        }
    }

    @Test
    void lookupIsCaseInsensitiveAndTrimmed() {
        assertEquals(LogCategory.CONFESSION, LogCategory.fromSlug("  Confession ").orElseThrow());
    }

    @Test
    void unknownSlugIsEmpty() {
        assertTrue(LogCategory.fromSlug("does-not-exist").isEmpty());
        assertTrue(LogCategory.fromSlug(null).isEmpty());
    }
}
