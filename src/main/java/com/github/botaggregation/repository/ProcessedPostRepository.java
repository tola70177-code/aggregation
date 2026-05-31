package com.github.botaggregation.repository;

import com.github.botaggregation.entity.ProcessedPost;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedPostRepository extends JpaRepository<ProcessedPost, Long> {

    boolean existsBySourceChatIdAndSourceMessageId(Long sourceChatId, Long sourceMessageId);
}
