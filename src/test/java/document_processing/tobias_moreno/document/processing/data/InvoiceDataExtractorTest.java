package document_processing.tobias_moreno.document.processing.data;

import document_processing.tobias_moreno.document.processing.PdfTextExtractor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceDataExtractorTest {

    private static String facturaCRawText;
    private final InvoiceDataExtractor extractor = new InvoiceDataExtractor();

    @BeforeAll
    static void loadFixture() throws Exception {
        PdfTextExtractor pdf = new PdfTextExtractor();
        try (InputStream in = InvoiceDataExtractorTest.class.getResourceAsStream("/fixtures/factura-c.pdf")) {
            assertThat(in).as("fixture factura-c.pdf must be on the test classpath").isNotNull();
            facturaCRawText = pdf.extract(in);
        }
    }

    @Test
    void extractsAllFieldsFromFacturaC() {
        Optional<ExtractedDocument> result = extractor.extract(facturaCRawText);

        assertThat(result).isPresent();
        ExtractedDocument extracted = result.get();
        assertThat(extracted.type()).isEqualTo(DocumentType.INVOICE);

        InvoiceData data = (InvoiceData) extracted.data();
        assertThat(data.invoiceNumber()).isEqualTo("00001-00000001");
        assertThat(data.issueDate()).isEqualTo(LocalDate.of(2026, 2, 25));
        assertThat(data.dueDate()).isEqualTo(LocalDate.of(2026, 3, 3));
        assertThat(data.total()).isEqualByComparingTo(new BigDecimal("850000.00"));
        assertThat(data.subtotal()).isEqualByComparingTo(new BigDecimal("850000.00"));
        assertThat(data.currency()).isEqualTo("ARS");
        assertThat(data.issuerCuit()).isEqualTo("20428563787");
        assertThat(data.customerCuit()).isEqualTo("20111111112");
        assertThat(data.cae()).isEqualTo("86096124599717");
    }

    @Test
    void returnsEmptyWhenTextHasNoFacturaMarker() {
        Optional<ExtractedDocument> result = extractor.extract("Esto es un recibo, no una factura.\nCUIT: 20428563787");

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenFacturaButNoValidCuit() {
        String text = "FACTURA C\nCUIT: 00000000000\nImporte Total: 100,00";

        Optional<ExtractedDocument> result = extractor.extract(text);

        assertThat(result).isEmpty();
    }

    @Test
    void parseArAmountHandlesThousandsAndDecimals() {
        assertThat(InvoiceDataExtractor.parseArAmount("850000,00")).isEqualByComparingTo("850000.00");
        assertThat(InvoiceDataExtractor.parseArAmount("1.234.567,89")).isEqualByComparingTo("1234567.89");
        assertThat(InvoiceDataExtractor.parseArAmount("0,00")).isEqualByComparingTo("0");
    }

    @Test
    void cuitValidationRejectsInvalidAndAcceptsValid() {
        assertThat(InvoiceDataExtractor.isValidCuit("00000000000")).isFalse();
        assertThat(InvoiceDataExtractor.isValidCuit("12345678901")).isFalse();
        assertThat(InvoiceDataExtractor.isValidCuit("20428563787")).isTrue();
        assertThat(InvoiceDataExtractor.isValidCuit("20111111112")).isTrue();
    }
}
