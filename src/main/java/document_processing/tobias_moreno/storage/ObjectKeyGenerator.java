package document_processing.tobias_moreno.storage;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class ObjectKeyGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final Clock clock;

    public ObjectKeyGenerator() {
        this(Clock.systemUTC());
    }

    public ObjectKeyGenerator(Clock clock) {
        this.clock = clock;
    }

    public String keyFor(UUID documentId) {
        String datePartition = LocalDate.now(clock.withZone(ZoneOffset.UTC)).format(DATE_FORMAT);
        return datePartition + "/" + documentId;
    }
}
