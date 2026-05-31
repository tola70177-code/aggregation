package com.github.botaggregation.repository;

import com.github.botaggregation.entity.SourceChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SourceChannelRepository extends JpaRepository<SourceChannel, Long> {

    List<SourceChannel> findAllByEnabledTrue();

    boolean existsByChannelId(Long channelId);

    Optional<SourceChannel> findByChannelId(Long channelId);
}
