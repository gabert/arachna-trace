package com.github.gabert.arachna.trace.demo.library.repository;

import com.github.gabert.arachna.trace.demo.library.model.AuthorEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthorRepository extends JpaRepository<AuthorEntity, Long> {

    Optional<AuthorEntity> findByName(String name);
}
