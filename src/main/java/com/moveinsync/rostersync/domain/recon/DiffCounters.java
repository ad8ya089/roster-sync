package com.moveinsync.rostersync.domain.recon;

public record DiffCounters(long activeBefore, long missingActive, long creates, long updates,
                           long heals, long unchanged, long staleSkipped) { }
