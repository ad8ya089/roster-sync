package com.moveinsync.rostersync.domain.recon;

import com.moveinsync.rostersync.domain.model.HrSnapshot;

public record PlannedAction(String employeeId, ActionType action, Long fileVersion, Long expectedDbVersion, HrSnapshot payload) { }
