package com.github.botaggregation.repository;

import com.github.botaggregation.entity.PostTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PostTemplateRepository extends JpaRepository<PostTemplate, Long> {

    default Optional<PostTemplate> findCurrent() {
        return findAll().stream().findFirst();
    }
}
