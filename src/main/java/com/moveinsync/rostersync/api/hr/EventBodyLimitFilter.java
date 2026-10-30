package com.moveinsync.rostersync.api.hr;
import com.moveinsync.rostersync.api.error.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

public final class EventBodyLimitFilter extends OncePerRequestFilter {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private final JsonMapper mapper;
    public EventBodyLimitFilter(JsonMapper mapper) { this.mapper=mapper; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/api/v1/hr/events".equals(request.getServletPath());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) { tooLarge(response); return; }
        // Read at most limit+1 bytes, including chunked requests. Jackson never sees an unbounded stream.
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) { tooLarge(response); return; }
        var input = new ByteArrayInputStream(body);
        var capped = new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    public int read() { return input.read(); }
                    public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
                    public boolean isFinished() { return input.available() == 0; }
                    public boolean isReady() { return true; }
                    public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous request body"); }
                };
            }
        };
        chain.doFilter(capped, response);
    }
    private void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(413); response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.problem(ErrorCode.PAYLOAD_TOO_LARGE));
        // Commit the error before Tomcat discards the remaining oversized request body.
        response.flushBuffer();
    }
}
