package com.github.botaggregation.repository;

import com.github.botaggregation.entity.DestinationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DestinationChannelRepository extends JpaRepository<DestinationChannel, Long> {

    default Optional<DestinationChannel> findCurrent() {
        return findAll().stream().findFirst();
    }
}
