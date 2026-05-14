package com.github.gabert.arachna.trace.demo.library.repository;

import com.github.gabert.arachna.trace.demo.library.model.BookEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookRepository extends JpaRepository<BookEntity, Long> {

    List<BookEntity> findByAuthorId(Long authorId);

    List<BookEntity> findByYearBetween(int from, int to);
}
