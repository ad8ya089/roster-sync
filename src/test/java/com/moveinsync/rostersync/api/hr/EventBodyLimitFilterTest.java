package com.moveinsync.rostersync.api.hr;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class EventBodyLimitFilterTest {
    @Test void rejectsAnOversizedContentLengthBeforeReadingTheBody() throws Exception {
        var request=new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return EventBodyLimitFilter.MAX_BYTES+1L; }
            @Override public jakarta.servlet.ServletInputStream getInputStream() { throw new AssertionError("Oversized declared body must not be read"); }
        };
        request.setMethod("POST"); request.setServletPath("/api/v1/hr/events");
        var response=new MockHttpServletResponse();
        new EventBodyLimitFilter(new JsonMapper()).doFilter(request,response,(ignored,reply)->{throw new AssertionError("Rejected request reached controller");});
        assertThat(response.getStatus()).isEqualTo(413);
    }
    @Test void capsChunkedBodiesAndPreservesAnExactlyAtLimitBody() throws Exception {
        for (int extra : new int[]{0,1}) {
            var request=new MockHttpServletRequest() {
                @Override public long getContentLengthLong() { return -1; }
                @Override public int getContentLength() { return -1; }
            };
            request.setMethod("POST"); request.setServletPath("/api/v1/hr/events");
            request.setContent(new byte[EventBodyLimitFilter.MAX_BYTES+extra]);
            var response=new MockHttpServletResponse(); var called=new java.util.concurrent.atomic.AtomicBoolean();
            new EventBodyLimitFilter(new JsonMapper()).doFilter(request,response,(wrapped,reply)->{
                called.set(true); assertThat(wrapped.getInputStream().readAllBytes()).hasSize(EventBodyLimitFilter.MAX_BYTES);
            });
            assertThat(called.get()).isEqualTo(extra==0);
            assertThat(response.getStatus()).isEqualTo(extra==0 ? 200 : 413);
        }
    }
}
