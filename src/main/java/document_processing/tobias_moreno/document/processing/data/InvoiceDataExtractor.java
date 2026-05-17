package document_processing.tobias_moreno.document.processing.data;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class InvoiceDataExtractor implements DocumentDataExtractor {

    private static final DateTimeFormatter AR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b(\\d{2}/\\d{2}/\\d{4})\\b");
    private static final Pattern CUIT_PATTERN = Pattern.compile("\\b(\\d{11})\\b");
    private static final Pattern CAE_PATTERN = Pattern.compile("(?<!\\d)(\\d{14})(?!\\d)");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{3})+,\\d{2}|\\d+,\\d{2})\\b");
    private static final Pattern INVOICE_NUMBER_PATTERN = Pattern.compile(
            "Punto\\s+de\\s+Venta:\\s*(?:(\\d{4,5})\\s*Comp\\.\\s*Nro:\\s*(\\d{8})"
                    + "|Comp\\.\\s*Nro:\\s*(\\d{4,5})\\s+(\\d{8}))");
    private static final Pattern ISSUER_NAME_PATTERN = Pattern.compile(
            "(?:^|[\\r\\n])Raz[oó]n\\s+Social:\\s*[\\r\\n]+\\s*([^\\r\\n]+)");
    private static final Pattern CUSTOMER_NAME_PATTERN = Pattern.compile(
            "Apellido\\s+y\\s+Nombre\\s*/\\s*Raz[oó]n\\s+Social:\\s*(?:[\\r\\n]+[^\\r\\n]*)*?[\\r\\n]+(\\d{2}/\\d{2}/\\d{4})[\\r\\n]+\\d{11}[\\r\\n]+([^\\r\\n]+)");

    private static final int CURRENCY_FACTURA_C = 0;

    @Override
    public Optional<ExtractedDocument> extract(String rawText) {
        if (rawText == null) {
            return Optional.empty();
        }
        String upper = rawText.toUpperCase();
        if (!upper.contains("FACTURA")) {
            return Optional.empty();
        }

        List<String> validCuits = findAll(CUIT_PATTERN, rawText).stream()
                .filter(InvoiceDataExtractor::isValidCuit)
                .toList();
        if (validCuits.size() < 2) {
            return Optional.empty();
        }
        String issuerCuit = validCuits.get(0);
        String customerCuit = validCuits.get(1);

        String invoiceNumber = matchInvoiceNumber(rawText);
        if (invoiceNumber == null) {
            return Optional.empty();
        }

        List<LocalDate> dates = findAll(DATE_PATTERN, rawText).stream()
                .map(this::parseDateSafely)
                .filter(d -> d != null)
                .toList();
        LocalDate dueDate = dates.size() >= 3 ? dates.get(2) : null;
        LocalDate issueDate = dates.size() >= 4 ? dates.get(3) : null;
        if (issueDate == null) {
            return Optional.empty();
        }

        List<BigDecimal> amounts = findAll(AMOUNT_PATTERN, rawText).stream()
                .map(this::parseAmountSafely)
                .filter(a -> a != null)
                .toList();
        if (amounts.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal total = amounts.stream().max(Comparator.naturalOrder()).orElse(null);
        BigDecimal subtotal = total;

        String cae = firstGroup(CAE_PATTERN, rawText, 1);
        if (cae == null) {
            return Optional.empty();
        }

        String issuerName = firstGroup(ISSUER_NAME_PATTERN, rawText, 1);
        String customerName = matchCustomerName(rawText);

        InvoiceData data = new InvoiceData(
                invoiceNumber,
                issueDate,
                dueDate,
                subtotal,
                total,
                "ARS",
                issuerCuit,
                issuerName,
                customerCuit,
                customerName,
                cae
        );
        return Optional.of(new ExtractedDocument(DocumentType.INVOICE, data));
    }

    private String matchInvoiceNumber(String rawText) {
        Matcher m = INVOICE_NUMBER_PATTERN.matcher(rawText);
        if (!m.find()) {
            return null;
        }
        String pv = m.group(1) != null ? m.group(1) : m.group(3);
        String nro = m.group(2) != null ? m.group(2) : m.group(4);
        if (pv == null || nro == null) {
            return null;
        }
        return pv + "-" + nro;
    }

    private String matchCustomerName(String rawText) {
        Matcher m = CUSTOMER_NAME_PATTERN.matcher(rawText);
        if (m.find()) {
            return m.group(2).trim();
        }
        return null;
    }

    private LocalDate parseDateSafely(String value) {
        try {
            return LocalDate.parse(value, AR_DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static BigDecimal parseArAmount(String value) {
        String normalized = value.replace(".", "").replace(",", ".");
        return new BigDecimal(normalized);
    }

    private BigDecimal parseAmountSafely(String value) {
        try {
            return parseArAmount(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> findAll(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String firstGroup(Pattern pattern, String text, int group) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(group) : null;
    }

    static boolean isValidCuit(String cuit) {
        if (cuit == null || cuit.length() != 11) {
            return false;
        }
        if ("00000000000".equals(cuit)) {
            return false;
        }
        int[] weights = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += Character.digit(cuit.charAt(i), 10) * weights[i];
        }
        int remainder = sum % 11;
        int expected = remainder == 0 ? 0 : (remainder == 1 ? 9 : 11 - remainder);
        int actual = Character.digit(cuit.charAt(10), 10);
        return actual == expected;
    }
}
