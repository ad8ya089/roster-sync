package com.moveinsync.rostersync;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Only on the test classpath: exercises error handling without production fault endpoints. */
@RestController
@Profile("test")
@RequestMapping("/api/v1/test-errors")
public class ErrorTestEndpoints {
    @GetMapping("/failure") @PreAuthorize("hasRole('OPERATOR')")
    public String failure(@RequestParam String input) { throw new IllegalStateException(input); }

    @GetMapping("/dependency") @PreAuthorize("hasRole('OPERATOR')")
    public String dependency(@RequestParam String input) { throw new DataAccessResourceFailureException(input); }

    @GetMapping("/upload") @PreAuthorize("hasRole('OPERATOR')")
    public String upload(@RequestParam String input) { throw new MaxUploadSizeExceededException(1); }

    @GetMapping("/numbers/{number}") @PreAuthorize("hasRole('OPERATOR')")
    public long number(@PathVariable long number) { return number; }
}
