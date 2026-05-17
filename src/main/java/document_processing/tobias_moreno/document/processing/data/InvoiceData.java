package document_processing.tobias_moreno.document.processing.data;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InvoiceData(
        @NotBlank @Pattern(regexp = "\\d{4,5}-\\d{8}") String invoiceNumber,
        @NotNull @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate issueDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate dueDate,
        @NotNull @DecimalMin("0.0") BigDecimal subtotal,
        @NotNull @DecimalMin("0.0") BigDecimal total,
        @NotBlank String currency,
        @NotBlank @Pattern(regexp = "\\d{11}") String issuerCuit,
        String issuerName,
        @NotBlank @Pattern(regexp = "\\d{11}") String customerCuit,
        String customerName,
        @NotBlank @Pattern(regexp = "\\d{14}") String cae
) {
}
