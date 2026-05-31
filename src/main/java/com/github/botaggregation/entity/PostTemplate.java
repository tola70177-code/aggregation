package com.github.botaggregation.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "post_template")
@Getter
@Setter
@NoArgsConstructor
public class PostTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_text", columnDefinition = "TEXT")
    private String templateText;

    @Column(name = "result_text", columnDefinition = "TEXT")
    private String resultText;

    @Column(name = "result_html", columnDefinition = "TEXT")
    private String resultHtml;

    @Column(name = "field_names", columnDefinition = "TEXT")
    private String fieldNames;

    @Column(name = "field_examples", columnDefinition = "TEXT")
    private String fieldExamples;
}
