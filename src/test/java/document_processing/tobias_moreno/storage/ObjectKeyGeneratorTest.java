package document_processing.tobias_moreno.storage;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectKeyGeneratorTest {

    @Test
    void embedsDocumentIdAndPartitionsByUtcDate() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-16T22:30:00Z"), ZoneOffset.UTC);
        ObjectKeyGenerator generator = new ObjectKeyGenerator(fixed);
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");

        String key = generator.keyFor(id);

        assertThat(key).isEqualTo("2026/05/16/11111111-2222-3333-4444-555555555555");
    }

    @Test
    void usesUtcEvenWhenClockIsInOtherZone() {
        // 2026-05-16T23:00:00-05:00 == 2026-05-17T04:00:00Z
        Clock buenosAires = Clock.fixed(Instant.parse("2026-05-17T04:00:00Z"), ZoneId.of("America/Argentina/Buenos_Aires"));
        ObjectKeyGenerator generator = new ObjectKeyGenerator(buenosAires);
        UUID id = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        String key = generator.keyFor(id);

        assertThat(key).startsWith("2026/05/17/");
        assertThat(key).endsWith(id.toString());
    }
}
