package com.moveinsync.rostersync.application.sideeffects;

import com.moveinsync.rostersync.api.error.*;
import com.moveinsync.rostersync.domain.model.*;
import com.moveinsync.rostersync.infrastructure.persistence.EmployeeRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {
    private final JdbcClient jdbc;
    private final EmployeeRepository employees;
    private final Clock clock;
    public BookingService(JdbcClient jdbc,EmployeeRepository employees,Clock clock) {this.jdbc=jdbc;this.employees=employees;this.clock=clock;}
    @Transactional
    public BookingView create(UUID tenantId,String employeeId,Instant pickupAt) {
        var employee=employees.findForShare(tenantId,employeeId).orElseThrow(NotFoundException::new);
        if(!"ACTIVE".equals(employee.snapshot().status())) throw new ConflictException(ErrorCode.EMPLOYEE_INACTIVE);
        return jdbc.sql("""
                INSERT INTO cab_booking(tenant_id,employee_external_id,pickup_at,status,updated_at)
                VALUES (?,?,?,'SCHEDULED',?) RETURNING id,employee_external_id,pickup_at,status,cancel_reason
                """).params(tenantId,employeeId,Timestamp.from(pickupAt),Timestamp.from(clock.instant())).query(BookingService::booking).single();
    }
    @Transactional(readOnly=true)
    public List<BookingView> list(UUID tenantId,String employeeId,Role role,String selfId) {
        if(role==Role.EMPLOYEE && !employeeId.equals(selfId)) throw new NotFoundException();
        employees.findByTenantIdAndExternalId(tenantId,employeeId).orElseThrow(NotFoundException::new);
        return jdbc.sql("SELECT id,employee_external_id,pickup_at,status,cancel_reason FROM cab_booking WHERE tenant_id=? AND employee_external_id=? ORDER BY pickup_at,id")
                .params(tenantId,employeeId).query(BookingService::booking).list();
    }
    @Transactional(readOnly=true)
    public List<SeatView> seats(UUID tenantId,String routeCode) {
        var seats=jdbc.sql("SELECT seat_no,employee_external_id FROM route_seat WHERE tenant_id=? AND route_code=? ORDER BY seat_no")
                .params(tenantId,routeCode).query((row,n)->new SeatView(row.getInt(1),row.getString(2))).list();
        if(seats.isEmpty()) throw new NotFoundException();
        return seats;
    }
    private static BookingView booking(java.sql.ResultSet row,int number) throws java.sql.SQLException {
        return new BookingView(row.getLong(1),row.getString(2),row.getTimestamp(3).toInstant(),row.getString(4),row.getString(5));
    }
    public record BookingView(long id,String employeeId,Instant pickupAt,String status,String cancelReason) { }
    public record SeatView(int seatNo,String employeeId) { }
}
