package com.github.botaggregation.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "processed_posts",
        uniqueConstraints = @UniqueConstraint(columnNames = {"source_chat_id", "source_message_id"}))
@Getter
@Setter
@NoArgsConstructor
public class ProcessedPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_chat_id", nullable = false)
    private Long sourceChatId;

    @Column(name = "source_message_id", nullable = false)
    private Long sourceMessageId;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt = LocalDateTime.now();
}
