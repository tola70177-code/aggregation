package com.github.botaggregation.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "processed_posts")
@Getter
@Setter
@NoArgsConstructor
public class ProcessedPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_channel_id", nullable = false)
    private Long sourceChannelId;

    @Column(name = "content_link", length = 2048)
    private String contentLink;

    @Column(name = "content_fields", columnDefinition = "TEXT")
    private String contentFields;
}
