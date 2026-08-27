package kr.lostory.backend.partner.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import kr.lostory.backend.audit.application.P0AuditService;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.config.PartnerProperties;
import kr.lostory.backend.lostcenter.domain.LostCenterRepository;
import kr.lostory.backend.partner.domain.CenterActivationToken;
import kr.lostory.backend.partner.domain.CenterActivationTokenRepository;
import kr.lostory.backend.partner.domain.CenterPartnership;
import kr.lostory.backend.partner.domain.CenterPartnershipRepository;
import kr.lostory.backend.partner.domain.PartnershipStatus;
import kr.lostory.backend.partner.presentation.ActivatePartnerManagerRequest;
import kr.lostory.backend.partner.presentation.CreatePartnerCenterRequest;
import kr.lostory.backend.partner.presentation.PartnerCenterResponses;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PartnerCenterService {

    private static final Duration ACTIVATION_TTL = Duration.ofHours(24);
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();

    private final CenterPartnershipRepository partnerships;
    private final CenterActivationTokenRepository activationTokens;
    private final LostCenterRepository centers;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final P0AuditService audit;
    private final PartnerProperties properties;
    private final Clock clock;
    private final SecureRandom partnerSecureRandom;

    @Transactional
    public PartnerCenterResponses.Created create(Long adminId, CreatePartnerCenterRequest request) {
        Long centerId = Long.valueOf(request.centerId());
        if (!centers.existsById(centerId)) {
            throw concealedNotFound();
        }
        String email = request.manager().email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw new LostoryException(ErrorCode.DUPLICATE_EMAIL);
        }
        CenterPartnership partnership = partnerships.saveAndFlush(new CenterPartnership(
                centerId, email, request.manager().displayName().trim(), clock.instant()));
        audit.partnerCenterCreated(adminId, partnership.getId());
        return new PartnerCenterResponses.Created(
                partnership.getId().toString(), centerId.toString(), partnership.getStatus().name(), email);
    }

    @Transactional
    public PartnerCenterResponses.Approved approve(Long adminId, Long partnershipId) {
        CenterPartnership partnership = partnerships.findByIdForUpdate(partnershipId)
                .orElseThrow(PartnerCenterService::concealedNotFound);
        if (partnership.getStatus() == PartnershipStatus.ACTIVE) {
            throw new LostoryException(ErrorCode.INVALID_STATE);
        }
        activationTokens.findCurrentForUpdate(partnershipId).ifPresent(current -> {
            current.replace();
            activationTokens.saveAndFlush(current);
        });
        Instant now = clock.instant();
        byte[] rawToken = new byte[32];
        partnerSecureRandom.nextBytes(rawToken);
        String encodedToken = TOKEN_ENCODER.encodeToString(rawToken);
        Instant expiresAt = now.plus(ACTIVATION_TTL);
        activationTokens.saveAndFlush(new CenterActivationToken(partnershipId, sha256(rawToken), now, expiresAt));
        partnership.awaitActivation(now);
        partnerships.saveAndFlush(partnership);
        audit.partnerCenterApproved(adminId, partnershipId);
        return new PartnerCenterResponses.Approved(
                partnershipId.toString(), partnership.getStatus().name(),
                properties.activationBaseUrl() + "/" + encodedToken, expiresAt);
    }

    @Transactional
    public PartnerCenterResponses.Activated activate(String encodedToken, ActivatePartnerManagerRequest request) {
        byte[] rawToken = decodeToken(encodedToken);
        byte[] hash = sha256(rawToken);
        Long partnershipId = activationTokens.findPartnershipIdByTokenHash(hash)
                .orElseThrow(PartnerCenterService::concealedNotFound);
        CenterPartnership partnership = partnerships.findByIdForUpdate(partnershipId)
                .orElseThrow(PartnerCenterService::concealedNotFound);
        CenterActivationToken token = activationTokens.findByHashForUpdate(partnershipId, hash)
                .orElseThrow(PartnerCenterService::concealedNotFound);
        Instant now = clock.instant();
        if (partnership.getStatus() != PartnershipStatus.PENDING_ACTIVATION
                || token.isReplaced()
                || token.getConsumedAt() != null
                || !token.getExpiresAt().isAfter(now)) {
            throw concealedNotFound();
        }
        try {
            User manager = users.saveAndFlush(new User(
                    partnership.getManagerEmail(), passwordEncoder.encode(request.password()),
                    partnership.getManagerDisplayName(), UserRole.CENTER_MANAGER));
            token.consume(now);
            activationTokens.saveAndFlush(token);
            partnership.activate(manager.getId(), now);
            partnerships.saveAndFlush(partnership);
            audit.partnerManagerActivated(manager.getId(), partnershipId);
            return new PartnerCenterResponses.Activated(
                    partnershipId.toString(), partnership.getCenterId().toString(), manager.getId().toString(),
                    partnership.getStatus().name());
        } catch (DataIntegrityViolationException exception) {
            throw new LostoryException(ErrorCode.INVALID_STATE);
        }
    }

    private static byte[] decodeToken(String encodedToken) {
        try {
            byte[] rawToken = TOKEN_DECODER.decode(encodedToken);
            if (rawToken.length != 32 || !TOKEN_ENCODER.encodeToString(rawToken).equals(encodedToken)) {
                throw concealedNotFound();
            }
            return rawToken;
        } catch (IllegalArgumentException exception) {
            throw concealedNotFound();
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static LostoryException concealedNotFound() {
        return new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
