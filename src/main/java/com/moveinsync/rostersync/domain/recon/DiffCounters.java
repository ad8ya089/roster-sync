package com.moveinsync.rostersync.domain.recon;

public record DiffCounters(long activeBefore, long missingActive, long creates, long updates,
                           long heals, long advances, long unchanged, long staleSkipped) {
    public DiffCounters(long activeBefore,long missingActive,long creates,long updates,long heals,long unchanged,long staleSkipped) {
        this(activeBefore,missingActive,creates,updates,heals,0,unchanged,staleSkipped);
    }
    public static DiffCounters zero() {return new DiffCounters(0,0,0,0,0,0,0,0);}
    public DiffCounters plus(DiffCounters other) {return new DiffCounters(activeBefore+other.activeBefore,missingActive+other.missingActive,
            creates+other.creates,updates+other.updates,heals+other.heals,advances+other.advances,unchanged+other.unchanged,staleSkipped+other.staleSkipped);}
}
