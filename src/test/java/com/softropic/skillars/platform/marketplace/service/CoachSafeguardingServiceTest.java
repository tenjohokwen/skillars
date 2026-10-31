package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.filestorage.service.BlobDeletionOutboxSupport;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.marketplace.contract.CoachSafeguardingExpiringEvent;
import com.softropic.skillars.platform.marketplace.contract.CoachSafeguardingStatusResponse;
import com.softropic.skillars.platform.marketplace.contract.MarketplaceException;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSafeguardingCheck;
import com.softropic.skillars.platform.marketplace.repo.CoachSafeguardingCheckRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSafeguardingRequest;
import com.softropic.skillars.platform.marketplace.repo.CoachSafeguardingRequestRepository;
import com.softropic.skillars.platform.security.contract.util.Cryptopher;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoachSafeguardingServiceTest {

    private static final UUID COACH_ID = UUID.randomUUID();
    private static final long USER_ID = 7001L;
    private static final long ADMIN_ID = 9001L;
    private static final byte[] PDF = "%PDF-1.7 certificate".getBytes(StandardCharsets.US_ASCII);

    @Mock CoachProfileRepository coachProfileRepository;
    @Mock CoachSafeguardingCheckRepository checkRepository;
    @Mock CoachSafeguardingRequestRepository requestRepository;
    @Mock FileStorageService fileStorageService;
    @Mock BlobDeletionOutboxSupport blobDeletionOutboxSupport;
    @Mock ConfigService configService;
    @Mock PessimisticLockRetryer lockRetryer;
    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TransactionTemplate transactionTemplate;

    private final Cryptopher cryptopher = new Cryptopher("unit-test-secret");
    private CoachSafeguardingService service;

    @BeforeEach
    void setUp() {
        service = new CoachSafeguardingService(coachProfileRepository, checkRepository, requestRepository,
            fileStorageService, blobDeletionOutboxSupport, cryptopher, configService, lockRetryer, userRepository,
            eventPublisher, transactionTemplate);

        CoachProfile profile = new CoachProfile();
        profile.setId(COACH_ID);
        profile.setUserId(USER_ID);
        profile.setDisplayName("Coach Kim");
        profile.setCanonicalTimezone("Europe/Berlin");
        lenient().when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        lenient().when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(profile));
        lenient().when(configService.getBoundedLong(ConfigBounds.COACH_SAFEGUARDING_VALIDITY_YEARS)).thenReturn(5L);
        lenient().when(configService.getBoundedLong(ConfigBounds.COACH_SAFEGUARDING_UPLOAD_RETENTION_DAYS))
            .thenReturn(14L);
        lenient().when(requestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(checkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction((TransactionStatus) null);
        });
    }

    private static CoachSafeguardingRequest pendingRequest(String uploadKey) {
        CoachSafeguardingRequest r = new CoachSafeguardingRequest();
        r.setCoachId(COACH_ID);
        r.setStatus(CoachSafeguardingRequest.STATUS_PENDING);
        r.setCheckType("DE_EFZ");
        r.setRequestedAt(Instant.now().minusSeconds(60));
        if (uploadKey != null) {
            r.setUploadKey(uploadKey);
            r.setUploadStoredAt(Instant.now().minusSeconds(60));
            r.setUploadContentType("application/pdf");
        }
        return r;
    }

    private static CoachSafeguardingCheck check(Instant expiresAt) {
        CoachSafeguardingCheck c = new CoachSafeguardingCheck();
        c.setCoachId(COACH_ID);
        c.setCheckType("DE_EFZ");
        c.setInspectedAt(expiresAt.minus(Duration.ofDays(365)));
        c.setExpiresAt(expiresAt);
        c.setInspectedBy(ADMIN_ID);
        return c;
    }

    // ── status derivation ───────────────────────────────────────────────────────────────────────

    @Test
    void status_none_whenNothingOnFile_andCoachMayRequest() {
        CoachSafeguardingStatusResponse s = service.getOwnStatus(USER_ID);
        assertThat(s.status()).isEqualTo("NONE");
        assertThat(s.canRequest()).isTrue();
        assertThat(s.validUntil()).isNull();
    }

    @Test
    void status_verified_whileValid_andRenewalPendingStaysVerified() {
        when(checkRepository.findById(COACH_ID)).thenReturn(Optional.of(check(Instant.now().plus(Duration.ofDays(10)))));
        when(requestRepository.findById(COACH_ID)).thenReturn(Optional.of(pendingRequest(null)));

        CoachSafeguardingStatusResponse s = service.getOwnStatus(USER_ID);

        assertThat(s.status()).isEqualTo("VERIFIED");
        assertThat(s.renewalPending()).isTrue();
        assertThat(s.validUntil()).isNotNull();
        assertThat(s.canRequest()).isFalse(); // already pending
    }

    @Test
    void status_expired_afterExpiresAt_withNoRequest() {
        when(checkRepository.findById(COACH_ID)).thenReturn(Optional.of(check(Instant.now().minusSeconds(5))));

        CoachSafeguardingStatusResponse s = service.getOwnStatus(USER_ID);

        assertThat(s.status()).isEqualTo("EXPIRED");
        assertThat(s.canRequest()).isTrue();
    }

    @Test
    void status_pending_exposesUploadDeleteDate_andRejectedIsShown() {
        when(requestRepository.findById(COACH_ID)).thenReturn(Optional.of(pendingRequest("safeguarding/x.enc")));
        CoachSafeguardingStatusResponse pending = service.getOwnStatus(USER_ID);
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.uploadOnFile()).isTrue();
        assertThat(pending.uploadDeleteAt()).isAfter(Instant.now().plus(Duration.ofDays(13)));

        CoachSafeguardingRequest rejected = pendingRequest(null);
        rejected.setStatus(CoachSafeguardingRequest.STATUS_REJECTED);
        when(requestRepository.findById(COACH_ID)).thenReturn(Optional.of(rejected));
        CoachSafeguardingStatusResponse s = service.getOwnStatus(USER_ID);
        assertThat(s.status()).isEqualTo("REJECTED");
        assertThat(s.canRequest()).isTrue();
    }

    // ── coach request ───────────────────────────────────────────────────────────────────────────

    @Test
    void request_withUpload_storesOnlyCiphertext_underAPrivateKey() {
        service.requestVerification(USER_ID, PDF);

        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(fileStorageService).storeBytes(stored.capture(), key.capture(), eq("application/octet-stream"));
        assertThat(key.getValue()).startsWith("safeguarding/" + COACH_ID + "/").endsWith(".enc");
        String bucketContent = new String(stored.getValue(), StandardCharsets.UTF_8);
        assertThat(bucketContent).doesNotContain("%PDF").doesNotContain("certificate");

        ArgumentCaptor<CoachSafeguardingRequest> saved = ArgumentCaptor.forClass(CoachSafeguardingRequest.class);
        verify(requestRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().getUploadKey()).isEqualTo(key.getValue());
        assertThat(saved.getValue().getUploadContentType()).isEqualTo("application/pdf");
    }

    @Test
    void request_withoutFile_isTheVideoCallRoute_andStoresNothing() {
        service.requestVerification(USER_ID, null);

        verify(fileStorageService, never()).storeBytes(any(), anyString(), anyString());
        ArgumentCaptor<CoachSafeguardingRequest> saved = ArgumentCaptor.forClass(CoachSafeguardingRequest.class);
        verify(requestRepository).save(saved.capture());
        assertThat(saved.getValue().hasUpload()).isFalse();
    }

    @Test
    void request_replacingAPendingUpload_deletesThePreviousOne() {
        when(requestRepository.findById(COACH_ID)).thenReturn(Optional.of(pendingRequest("safeguarding/old.enc")));

        service.requestVerification(USER_ID, PDF);

        verify(blobDeletionOutboxSupport).enqueue(List.of("safeguarding/old.enc"));
    }

    @Test
    void request_whilePendingWithoutFile_isIdempotent() {
        when(requestRepository.findById(COACH_ID)).thenReturn(Optional.of(pendingRequest(null)));

        service.requestVerification(USER_ID, null);

        verify(requestRepository, never()).save(any());
    }

    @Test
    void request_refusedForAnErasedCoach() {
        when(coachProfileRepository.isRecapDataErased(COACH_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.requestVerification(USER_ID, PDF))
            .isInstanceOf(MarketplaceException.class)
            .extracting(e -> ((MarketplaceException) e).getErrorCode()).isEqualTo("marketplace.profileNotFound");
        verify(fileStorageService, never()).storeBytes(any(), anyString(), anyString());
    }

    @Test
    void request_refusedWhileTheCheckIsValidForLongerThanTheRenewalWindow() {
        when(checkRepository.findById(COACH_ID)).thenReturn(Optional.of(check(Instant.now().plus(Duration.ofDays(400)))));

        assertThatThrownBy(() -> service.requestVerification(USER_ID, null))
            .isInstanceOf(MarketplaceException.class)
            .extracting(e -> ((MarketplaceException) e).getErrorCode()).isEqualTo("marketplace.safeguardingStillValid");
    }

    @Test
    void request_renewalAllowedInsideTheWindow() {
        when(checkRepository.findById(COACH_ID)).thenReturn(Optional.of(check(Instant.now().plus(Duration.ofDays(60)))));

        service.requestVerification(USER_ID, null);

        verify(requestRepository).save(any());
    }

    @Test
    void sniff_acceptsPdfJpegPng_rejectsEverythingElse_andOversize() {
        assertThat(CoachSafeguardingService.sniffContentType(PDF)).isEqualTo("application/pdf");
        assertThat(CoachSafeguardingService.sniffContentType(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01}))
            .isEqualTo("image/jpeg");
        assertThat(CoachSafeguardingService.sniffContentType(
            new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0})).isEqualTo("image/png");

        assertThatThrownBy(() -> CoachSafeguardingService.sniffContentType("<html><script>".getBytes()))
            .isInstanceOf(MarketplaceException.class)
            .extracting(e -> ((MarketplaceException) e).getErrorCode()).isEqualTo("marketplace.safeguardingFileType");
        assertThatThrownBy(() -> CoachSafeguardingService.sniffContentType(new byte[0]))
            .isInstanceOf(MarketplaceException.class);
        byte[] big = new byte[(int) CoachSafeguardingService.MAX_UPLOAD_BYTES + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        assertThatThrownBy(() -> CoachSafeguardingService.sniffContentType(big))
            .isInstanceOf(MarketplaceException.class)
            .extracting(e -> ((MarketplaceException) e).getErrorCode()).isEqualTo("marketplace.safeguardingFileTooLarge");
    }

    @Test
    void withdraw_removesRequestRecordAndUpload() {
        CoachSafeguardingRequest request = pendingRequest("safeguarding/up.enc");
        CoachSafeguardingCheck existing = check(Instant.now().plus(Duration.ofDays(100)));
        when(requestRepository.findById(COACH_ID)).thenReturn(Optional.of(request));
        when(checkRepository.findById(COACH_ID)).thenReturn(Optional.of(existing));

        service.withdraw(USER_ID);

        verify(requestRepository).delete(request);
        verify(checkRepository).delete(existing);
        verify(blobDeletionOutboxSupport).enqueue(List.of("safeguarding/up.enc"));
    }

    // ── staff decisions ─────────────────────────────────────────────────────────────────────────

    @Test
    void markInspected_writesRecord_withConfiguredValidity_andDeletesTheUpload() {
        CoachSafeguardingRequest request = pendingRequest("safeguarding/up.enc");
        when(requestRepository.findById(COACH_ID)).thenReturn(Optional.of(request));
        when(configService.getBoundedLong(ConfigBounds.COACH_SAFEGUARDING_VALIDITY_YEARS)).thenReturn(3L);

        CoachSafeguardingService.Inspection result = service.markInspected(COACH_ID, ADMIN_ID);

        CoachSafeguardingCheck saved = result.check();
        assertThat(result.hadUpload()).isTrue();
        assertThat(saved.getInspectedBy()).isEqualTo(ADMIN_ID);
        assertThat(saved.getCheckType()).isEqualTo("DE_EFZ");
        assertThat(saved.getExpiresAt().atZone(ZoneOffset.UTC).toLocalDate())
            .isEqualTo(saved.getInspectedAt().atZone(ZoneOffset.UTC).toLocalDate().plusYears(3));
        assertThat(saved.getReminderSentAt()).isNull();
        verify(requestRepository).delete(request);
        verify(blobDeletionOutboxSupport).enqueue(List.of("safeguarding/up.enc"));
    }

    @Test
    void markInspected_renewal_resetsTheReminderStamp() {
        CoachSafeguardingCheck old = check(Instant.now().plus(Duration.ofDays(20)));
        old.setReminderSentAt(Instant.now().minusSeconds(100));
        when(requestRepository.findById(COACH_ID)).thenReturn(Optional.of(pendingRequest(null)));
        when(checkRepository.findById(COACH_ID)).thenReturn(Optional.of(old));

        CoachSafeguardingService.Inspection result = service.markInspected(COACH_ID, ADMIN_ID);

        assertThat(result.hadUpload()).isFalse();
        assertThat(result.check().getReminderSentAt()).isNull();
        assertThat(result.check().getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(5 * 365 - 2)));
    }

    @Test
    void markInspected_withoutAPendingRequest_is404_soNobodyGetsABadgeTheyDidNotAskFor() {
        assertThatThrownBy(() -> service.markInspected(COACH_ID, ADMIN_ID))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(checkRepository, never()).save(any());

        CoachSafeguardingRequest rejected = pendingRequest(null);
        rejected.setStatus(CoachSafeguardingRequest.STATUS_REJECTED);
        when(requestRepository.findById(COACH_ID)).thenReturn(Optional.of(rejected));
        assertThatThrownBy(() -> service.markInspected(COACH_ID, ADMIN_ID))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reject_keepsTheRequestAsRejected_andDeletesTheUpload() {
        CoachSafeguardingRequest request = pendingRequest("safeguarding/up.enc");
        when(requestRepository.findById(COACH_ID)).thenReturn(Optional.of(request));

        CoachSafeguardingRequest result = service.reject(COACH_ID);

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        assertThat(result.hasUpload()).isFalse();
        assertThat(result.getDecidedAt()).isNotNull();
        verify(blobDeletionOutboxSupport).enqueue(List.of("safeguarding/up.enc"));
        verify(checkRepository, never()).save(any());
    }

    @Test
    void loadDocument_decryptsWhatRequestVerificationEncrypted() {
        String key = "safeguarding/up.enc";
        when(requestRepository.findById(COACH_ID)).thenReturn(Optional.of(pendingRequest(key)));
        String ciphertext = cryptopher.encrypt(java.util.Base64.getEncoder().encodeToString(PDF));
        when(fileStorageService.downloadBytes(key)).thenReturn(ciphertext.getBytes(StandardCharsets.UTF_8));

        CoachSafeguardingService.Document doc = service.loadDocument(COACH_ID);

        assertThat(doc.bytes()).isEqualTo(PDF);
        assertThat(doc.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void loadDocument_404WhenThereIsNoUpload() {
        when(requestRepository.findById(COACH_ID)).thenReturn(Optional.of(pendingRequest(null)));
        assertThatThrownBy(() -> service.loadDocument(COACH_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void revoke_deletesTheRecord_or404s() {
        CoachSafeguardingCheck existing = check(Instant.now().plus(Duration.ofDays(100)));
        when(checkRepository.findById(COACH_ID)).thenReturn(Optional.of(existing));
        service.revoke(COACH_ID);
        verify(checkRepository).delete(existing);

        when(checkRepository.findById(COACH_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.revoke(COACH_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ── public read model ───────────────────────────────────────────────────────────────────────

    @Test
    void validUntilByCoach_mapsOnlyWhatTheQueryReturned() {
        Instant expiry = Instant.parse("2031-03-15T10:00:00Z");
        when(checkRepository.findValidExpiryByCoachIdIn(any(), any()))
            .thenReturn(List.<Object[]>of(new Object[] {COACH_ID, expiry}));

        Map<UUID, java.time.LocalDate> result = service.validUntilByCoach(List.of(COACH_ID, UUID.randomUUID()));

        assertThat(result).containsOnlyKeys(COACH_ID);
        assertThat(result.get(COACH_ID)).isEqualTo(java.time.LocalDate.of(2031, 3, 15));
        assertThat(service.validUntilByCoach(List.of())).isEmpty();
    }

    // ── erasure ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void eraseForCoach_deletesBothRowsAndEnqueuesTheUpload() {
        when(requestRepository.deleteByCoachIdReturningUploadKey(COACH_ID)).thenReturn(List.of("safeguarding/up.enc"));

        service.eraseForCoach(COACH_ID);

        verify(checkRepository).deleteByCoachIdNative(COACH_ID);
        verify(blobDeletionOutboxSupport).enqueue(List.of("safeguarding/up.enc"));
    }

    // ── jobs ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void sweep_dropsUploadsOlderThanRetention_keepsTheRequest() {
        CoachSafeguardingRequest stale = pendingRequest("safeguarding/stale.enc");
        stale.setUploadStoredAt(Instant.now().minus(Duration.ofDays(15)));
        when(requestRepository.findStaleUploads(any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(stale)).thenReturn(List.of());
        when(requestRepository.findById(COACH_ID)).thenReturn(Optional.of(stale));

        int deleted = service.sweepStaleUploads();

        assertThat(deleted).isEqualTo(1);
        assertThat(stale.hasUpload()).isFalse();
        assertThat(stale.isPending()).isTrue();
        verify(blobDeletionOutboxSupport).enqueue(List.of("safeguarding/stale.enc"));
    }

    @Test
    void sweep_skipsAnUploadThatWasReplacedSinceTheBatchWasRead() {
        CoachSafeguardingRequest listed = pendingRequest("safeguarding/old.enc");
        listed.setUploadStoredAt(Instant.now().minus(Duration.ofDays(20)));
        CoachSafeguardingRequest fresh = pendingRequest("safeguarding/new.enc"); // stored a minute ago
        when(requestRepository.findStaleUploads(any(Instant.class), any(Pageable.class))).thenReturn(List.of(listed));
        when(requestRepository.findById(COACH_ID)).thenReturn(Optional.of(fresh));

        assertThat(service.sweepStaleUploads()).isZero();
        verify(blobDeletionOutboxSupport, never()).enqueue(any());
    }

    @Test
    void reminder_publishesOnce_andStampsTheRecord() {
        Instant expiry = Instant.now().plus(Duration.ofDays(20));
        CoachSafeguardingCheck due = check(expiry);
        when(checkRepository.findDueForReminder(any(Instant.class), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(due));
        when(checkRepository.findById(COACH_ID)).thenReturn(Optional.of(due));
        User user = new User();
        user.setEmail("kim@example.com");
        user.setLangKey("de");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThat(service.sendExpiryReminders()).isEqualTo(1);

        assertThat(due.getReminderSentAt()).isNotNull();
        ArgumentCaptor<CoachSafeguardingExpiringEvent> event = ArgumentCaptor.forClass(CoachSafeguardingExpiringEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().email()).isEqualTo("kim@example.com");
        assertThat(event.getValue().langKey()).isEqualTo("de");
        assertThat(event.getValue().expiresAt()).isEqualTo(expiry);
    }

    @Test
    void reminder_skipsAlreadyRemindedAndUnreachableCoaches() {
        CoachSafeguardingCheck reminded = check(Instant.now().plus(Duration.ofDays(20)));
        reminded.setReminderSentAt(Instant.now());
        when(checkRepository.findDueForReminder(any(Instant.class), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(reminded));
        when(checkRepository.findById(COACH_ID)).thenReturn(Optional.of(reminded));
        assertThat(service.sendExpiryReminders()).isZero();

        CoachSafeguardingCheck unreachable = check(Instant.now().plus(Duration.ofDays(20)));
        when(checkRepository.findById(COACH_ID)).thenReturn(Optional.of(unreachable));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        assertThat(service.sendExpiryReminders()).isZero();
        assertThat(unreachable.getReminderSentAt()).isNull();
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void pendingRequests_delegatesToTheRepository() {
        Page<CoachSafeguardingRequest> page = new PageImpl<>(List.of(pendingRequest(null)));
        when(requestRepository.findPending(any(Pageable.class))).thenReturn(page);
        assertThat(service.pendingRequests(Pageable.unpaged())).isSameAs(page);
    }
}
