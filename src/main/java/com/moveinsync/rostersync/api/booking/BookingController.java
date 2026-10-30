package com.moveinsync.rostersync.api.booking;

import com.moveinsync.rostersync.application.sideeffects.BookingService;
import com.moveinsync.rostersync.infrastructure.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class BookingController {
    private final BookingService bookings;
    public BookingController(BookingService bookings) {this.bookings=bookings;}
    @PostMapping("/bookings") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('TRANSPORT_ADMIN')")
    public BookingService.BookingView create(@Valid @RequestBody CreateBooking request) {
        return bookings.create(TenantContext.currentUser().tenantId(),request.employeeId(),request.pickupAt());
    }
    @GetMapping("/employees/{employeeId}/bookings") @PreAuthorize("hasAnyRole('TRANSPORT_ADMIN','EMPLOYEE')")
    public List<BookingService.BookingView> list(@PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String employeeId) {
        var user=TenantContext.currentUser();return bookings.list(user.tenantId(),employeeId,user.role(),user.employeeId());
    }
    @GetMapping("/routes/{routeCode}/seats") @PreAuthorize("hasRole('TRANSPORT_ADMIN')")
    public List<BookingService.SeatView> seats(@PathVariable @Pattern(regexp="[A-Z0-9-]{1,32}") String routeCode) {
        return bookings.seats(TenantContext.currentUser().tenantId(),routeCode);
    }
    public record CreateBooking(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String employeeId,@NotNull Instant pickupAt) { }
}
