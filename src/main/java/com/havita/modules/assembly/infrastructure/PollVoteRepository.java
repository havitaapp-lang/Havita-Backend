package com.havita.modules.assembly.infrastructure;

import com.havita.modules.assembly.domain.PollVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface PollVoteRepository extends JpaRepository<PollVote, UUID> {

    List<PollVote> findByPollId(UUID pollId);

    boolean existsByPollIdAndPropertyId(UUID pollId, UUID propertyId);

    long countByPollId(UUID pollId);

    long countByPollOptionId(UUID pollOptionId);

    @Query("SELECT COALESCE(SUM(v.coefficient), 0) FROM PollVote v WHERE v.pollOption.id = :optionId")
    BigDecimal sumCoefficientByOptionId(UUID optionId);

    @Query("SELECT COALESCE(SUM(v.coefficient), 0) FROM PollVote v WHERE v.poll.id = :pollId")
    BigDecimal sumTotalVotedCoefficient(UUID pollId);
    /** Cierre E5 / D14: ¿la unidad ya voto alguna pregunta de esta asamblea? (regularizar una asistencia sin persona). */
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(v) > 0 FROM PollVote v WHERE v.property.id = :propertyId "
            + "AND v.poll.assembly.id = :assemblyId")
    boolean existsByPropertyAndAssembly(@org.springframework.data.repository.query.Param("propertyId") java.util.UUID propertyId,
                                        @org.springframework.data.repository.query.Param("assemblyId") java.util.UUID assemblyId);
}
