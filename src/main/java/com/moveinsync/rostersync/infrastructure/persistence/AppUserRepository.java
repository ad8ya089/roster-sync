package com.moveinsync.rostersync.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface AppUserRepository extends Repository<AppUserEntity, Long> {
    // Login is the sole tenant-less query: the username is globally unique in V1.
    Optional<AppUserEntity> findByUsername(String username);
}
