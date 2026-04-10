package org.twelve.entitir.worldone.db;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UiSessionRepository extends JpaRepository<UiSessionEntity, String> {

    /** 按创建时间返回全部 session。 */
    List<UiSessionEntity> findAllByOrderByCreatedAtAsc();
}
