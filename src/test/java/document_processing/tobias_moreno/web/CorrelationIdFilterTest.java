package document_processing.tobias_moreno.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void usesCallerSuppliedCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatesCorrelationIdWhenMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String header = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(header).isNotBlank();
        assertThat(header.length()).isGreaterThan(8);
    }

    @Test
    void truncatesOverlongCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String longValue = "x".repeat(200);
        request.addHeader(CorrelationIdFilter.HEADER, longValue);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String resultHeader = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(resultHeader).hasSize(CorrelationIdFilter.MAX_LENGTH);
        assertThat(resultHeader.chars().allMatch(c -> c == 'x')).isTrue();
    }
}
