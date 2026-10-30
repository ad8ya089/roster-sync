package com.moveinsync.rostersync.infrastructure.security;

public final class TenantContext {
    private static final ThreadLocal<CurrentUser> CURRENT = new ThreadLocal<>();
    private TenantContext() { }

    public static CurrentUser currentUser() {
        CurrentUser user = CURRENT.get();
        if (user == null) throw new IllegalStateException("No authenticated tenant context");
        return user;
    }
    static void set(CurrentUser user) { CURRENT.set(user); }
    static void clear() { CURRENT.remove(); }
}
