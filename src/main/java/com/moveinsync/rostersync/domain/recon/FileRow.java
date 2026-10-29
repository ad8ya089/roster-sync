package com.moveinsync.rostersync.domain.recon;

import com.moveinsync.rostersync.domain.model.HrSnapshot;

public record FileRow(String employeeId, long version, String hash, HrSnapshot snapshot) { }
