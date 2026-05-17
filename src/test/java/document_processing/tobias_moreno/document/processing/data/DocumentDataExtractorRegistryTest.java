package document_processing.tobias_moreno.document.processing.data;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentDataExtractorRegistryTest {

    private final Validator validator;

    DocumentDataExtractorRegistryTest() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            this.validator = factory.getValidator();
        }
    }

    @Test
    void matchingExtractorReturnsResult() {
        InvoiceData valid = sampleInvoice();
        DocumentDataExtractor extractor = text -> Optional.of(new ExtractedDocument(DocumentType.INVOICE, valid));
        DocumentDataExtractorRegistry registry = new DocumentDataExtractorRegistry(List.of(extractor), validator);

        Optional<ExtractedDocument> result = registry.classify("any", UUID.randomUUID());

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(DocumentType.INVOICE);
        assertThat(result.get().data()).isSameAs(valid);
    }

    @Test
    void throwingExtractorYieldsEmpty() {
        DocumentDataExtractor extractor = text -> { throw new IllegalStateException("boom"); };
        DocumentDataExtractorRegistry registry = new DocumentDataExtractorRegistry(List.of(extractor), validator);

        Optional<ExtractedDocument> result = registry.classify("any", UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    void payloadFailingValidationYieldsEmpty() {
        InvoiceData invalid = new InvoiceData(
                "BAD-NUMBER",
                LocalDate.of(2026, 1, 1),
                null,
                new BigDecimal("100"),
                new BigDecimal("100"),
                "ARS",
                "20428563787",
                "issuer",
                "20111111112",
                "customer",
                "86096124599717"
        );
        DocumentDataExtractor extractor = text -> Optional.of(new ExtractedDocument(DocumentType.INVOICE, invalid));
        DocumentDataExtractorRegistry registry = new DocumentDataExtractorRegistry(List.of(extractor), validator);

        Optional<ExtractedDocument> result = registry.classify("any", UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    void noExtractorYieldsEmpty() {
        DocumentDataExtractorRegistry registry = new DocumentDataExtractorRegistry(List.of(), validator);

        assertThat(registry.classify("any", UUID.randomUUID())).isEmpty();
    }

    @Test
    void blankTextYieldsEmpty() {
        DocumentDataExtractor extractor = text -> Optional.of(new ExtractedDocument(DocumentType.INVOICE, sampleInvoice()));
        DocumentDataExtractorRegistry registry = new DocumentDataExtractorRegistry(List.of(extractor), validator);

        assertThat(registry.classify("", UUID.randomUUID())).isEmpty();
        assertThat(registry.classify(null, UUID.randomUUID())).isEmpty();
    }

    private static InvoiceData sampleInvoice() {
        return new InvoiceData(
                "00001-00000001",
                LocalDate.of(2026, 2, 25),
                LocalDate.of(2026, 3, 3),
                new BigDecimal("850000.00"),
                new BigDecimal("850000.00"),
                "ARS",
                "20428563787",
                "MORENO TOBIAS EMILIANO",
                "20111111112",
                "CUIT GENERICO",
                "86096124599717"
        );
    }
}
