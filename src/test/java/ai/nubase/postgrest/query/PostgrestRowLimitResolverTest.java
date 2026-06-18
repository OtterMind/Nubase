package ai.nubase.postgrest.query;

import ai.nubase.postgrest.api.RangeHeader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostgrestRowLimitResolverTest {

    @Test
    void noRangeWithCap_appliesDefaultLimit() {
        var resolved = PostgrestRowLimitResolver.resolve(null, 1000);
        assertThat(resolved.offset()).isEqualTo(0L);
        assertThat(resolved.limit()).isEqualTo(1000L);
    }

    @Test
    void noRangeWithoutCap_leavesUnbounded() {
        var resolved = PostgrestRowLimitResolver.resolve(null, null);
        assertThat(resolved.offset()).isNull();
        assertThat(resolved.limit()).isNull();
    }

    @Test
    void clientRangeWithinCap_keepsRequestedLimit() {
        var range = RangeHeader.builder().unit("items").start(0L).end(9L).build();
        var resolved = PostgrestRowLimitResolver.resolve(range, 1000);
        assertThat(resolved.offset()).isEqualTo(0L);
        assertThat(resolved.limit()).isEqualTo(10L);
    }

    @Test
    void clientRangeAboveCap_truncatesToCap() {
        var range = RangeHeader.builder().unit("items").start(0L).end(4999L).build();
        var resolved = PostgrestRowLimitResolver.resolve(range, 1000);
        assertThat(resolved.offset()).isEqualTo(0L);
        assertThat(resolved.limit()).isEqualTo(1000L);
    }

    @Test
    void openEndedRangeWithCap_limitsToCapFromOffset() {
        var range = RangeHeader.builder().unit("items").start(10L).end(null).build();
        var resolved = PostgrestRowLimitResolver.resolve(range, 1000);
        assertThat(resolved.offset()).isEqualTo(10L);
        assertThat(resolved.limit()).isEqualTo(1000L);
    }

    @Test
    void legacyRangeWithoutCap_preservesExistingPlannerSemantics() {
        var range = RangeHeader.builder().unit("items").start(0L).end(9L).build();
        var resolved = PostgrestRowLimitResolver.resolve(range, null);
        assertThat(resolved.offset()).isEqualTo(0L);
        assertThat(resolved.limit()).isEqualTo(10L);
    }
}
