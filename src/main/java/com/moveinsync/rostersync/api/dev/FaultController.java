package com.moveinsync.rostersync.api.dev;

import com.moveinsync.rostersync.infrastructure.fault.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("demo") @RequestMapping("/api/v1/dev/faults")
public class FaultController {
    private final FaultInjector faults;
    public FaultController(FaultInjector faults) {this.faults=faults;}
    @GetMapping @PreAuthorize("hasRole('OPERATOR')")
    public List<FaultInjector.ArmedFault> list() {return faults.armed();}
    @PostMapping @PreAuthorize("hasRole('OPERATOR')")
    public List<FaultInjector.ArmedFault> arm(@Valid @RequestBody ArmFault request) {
        faults.arm(request.point(),request.mode(),request.oneShot()==null || request.oneShot(),request.delayMs()==null?1000:request.delayMs(),request.afterHits()==null?1:request.afterHits());
        return faults.armed();
    }
    @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('OPERATOR')")
    public void clear() {faults.clear();}
    public record ArmFault(@NotNull FaultPoint point,@NotNull FaultMode mode,Boolean oneShot,@Min(0) @Max(60000) Long delayMs,@Min(1) @Max(1000000) Integer afterHits) { }
}
