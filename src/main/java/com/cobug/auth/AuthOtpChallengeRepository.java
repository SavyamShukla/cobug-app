package com.cobug.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface AuthOtpChallengeRepository extends JpaRepository<AuthOtpChallenge, UUID> {
    Optional<AuthOtpChallenge> findByIdAndPurpose(UUID id, OtpPurpose purpose);
    Optional<AuthOtpChallenge> findTopByEmailAndPurposeOrderByLastSentAtDesc(String email, OtpPurpose purpose);
    void deleteByEmailAndPurpose(String email, OtpPurpose purpose);
}
