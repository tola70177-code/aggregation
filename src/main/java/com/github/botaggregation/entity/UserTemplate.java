package com.github.botaggregation.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_template")
@Getter
@Setter
@NoArgsConstructor
public class UserTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_text", columnDefinition = "TEXT", nullable = false)
    private String templateText;

    @Column(name = "fields", columnDefinition = "TEXT", nullable = false)
    private String fields;

    @Column(name = "has_image", nullable = false)
    private boolean hasImage = true;
}
