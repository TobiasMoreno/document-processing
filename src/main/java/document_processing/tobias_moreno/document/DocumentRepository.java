package document_processing.tobias_moreno.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    @Modifying
    @Transactional
    @Query("UPDATE Document d SET d.status = document_processing.tobias_moreno.document.DocumentStatus.PROCESSING, d.updatedAt = :at "
            + "WHERE d.id = :id AND d.status = document_processing.tobias_moreno.document.DocumentStatus.UPLOADED")
    int markAsProcessing(@Param("id") UUID id, @Param("at") Instant at);

    @Modifying
    @Transactional
    @Query("UPDATE Document d SET d.status = document_processing.tobias_moreno.document.DocumentStatus.PROCESSED, d.processedAt = :at, d.updatedAt = :at "
            + "WHERE d.id = :id")
    int markAsProcessed(@Param("id") UUID id, @Param("at") Instant at);

    @Modifying
    @Transactional
    @Query("UPDATE Document d SET d.status = document_processing.tobias_moreno.document.DocumentStatus.FAILED, d.errorMessage = :message, d.processedAt = :at, d.updatedAt = :at "
            + "WHERE d.id = :id")
    int markAsFailed(@Param("id") UUID id, @Param("message") String message, @Param("at") Instant at);
}
