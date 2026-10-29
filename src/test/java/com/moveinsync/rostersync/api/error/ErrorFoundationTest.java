package com.moveinsync.rostersync.api.error;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ErrorFoundationTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new CorrelationIdFilter()).build();
    }

    @Test
    void returnsProblemDetailsWithSuppliedUuidAndClearsMdc() throws Exception {
        String id = "058c49c3-87bf-4ba2-9abc-123456789012";
        mvc.perform(get("/missing").header(CorrelationIdFilter.HEADER, id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string(CorrelationIdFilter.HEADER, id))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value(id));
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void replacesUntrustedCorrelationTextAndHidesExceptionMessage() throws Exception {
        var response = mvc.perform(get("/failure").header(CorrelationIdFilter.HEADER, "canary.person@pii.test"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse();
        UUID.fromString(response.getHeader(CorrelationIdFilter.HEADER));
        assertThat(response.getContentAsString()).doesNotContain("canary.person@pii.test", "IllegalStateException");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatesUuidWhenNoHeaderIsSupplied() throws Exception {
        var response = mvc.perform(get("/missing")).andReturn().getResponse();
        assertThat(UUID.fromString(response.getHeader(CorrelationIdFilter.HEADER))).isNotNull();
    }

    @RestController
    static class TestController {
        @GetMapping("/missing")
        String missing() { throw new NotFoundException(); }

        @GetMapping("/failure")
        String failure() { throw new IllegalStateException("canary.person@pii.test"); }
    }
}
