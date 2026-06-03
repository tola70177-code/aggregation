package com.github.botaggregation.repository;

import com.github.botaggregation.entity.UserTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserTemplateRepository extends JpaRepository<UserTemplate, Long> {

    default Optional<UserTemplate> findCurrent() {
        return findAll().stream().findFirst();
    }
}
