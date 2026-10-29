package com.moveinsync.rostersync.domain.recon;

import com.moveinsync.rostersync.domain.model.EmploymentStatus;
import com.moveinsync.rostersync.domain.model.HrSnapshot;
import java.time.Instant;

public record DbRow(String employeeId, long hrVersion, EmploymentStatus status, Instant hrUpdatedAt, HrSnapshot snapshot) { }
