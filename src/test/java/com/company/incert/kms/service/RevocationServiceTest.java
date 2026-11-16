package com.company.incert.kms.service;

import com.company.incert.kms.entity.Certificate;
import com.company.incert.kms.entity.CertificateStatus;
import com.company.incert.kms.entity.RevocationReason;
import com.company.incert.kms.repository.CertificateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RevocationServiceTest {

    private CertificateRepository certRepository;
    private RevocationService service;

    @BeforeEach
    void setUp() {
        certRepository = mock(CertificateRepository.class);
        service = new RevocationService(certRepository);
    }

    @Test
    void revoke_validCert_updatesStatusAndIncrementsSequence() {
        Certificate cert = Certificate.builder()
                .serialNumberHex("01a2b3")
                .status(CertificateStatus.GOOD)
                .sequence(1L)
                .build();
        when(certRepository.findBySerialNumberHex("01a2b3")).thenReturn(Optional.of(cert));
        when(certRepository.save(any(Certificate.class))).thenAnswer(inv -> inv.getArgument(0));

        Certificate result = service.revoke("01a2b3", RevocationReason.KEY_COMPROMISE);

        assertThat(result.getStatus()).isEqualTo(CertificateStatus.REVOKED);
        assertThat(result.getRevocationReason()).isEqualTo(RevocationReason.KEY_COMPROMISE);
        assertThat(result.getSequence()).isEqualTo(2L);
        verify(certRepository, times(1)).save(cert);
    }

    @Test
    void revoke_nonExistentCert_throwsCertificateNotFoundException() {
        when(certRepository.findBySerialNumberHex("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke("unknown", RevocationReason.UNSPECIFIED))
                .isInstanceOf(RevocationService.CertificateNotFoundException.class)
                .hasMessageContaining("Certificate not found");
        verify(certRepository, never()).save(any());
    }

    @Test
    void unrevoke_certificateHold_revertsToGood() {
        Certificate cert = Certificate.builder()
                .serialNumberHex("02b3c4")
                .status(CertificateStatus.REVOKED)
                .revocationReason(RevocationReason.CERTIFICATE_HOLD)
                .revocationTime(Instant.now())
                .sequence(2L)
                .build();
        when(certRepository.findBySerialNumberHex("02b3c4")).thenReturn(Optional.of(cert));
        when(certRepository.save(any(Certificate.class))).thenAnswer(inv -> inv.getArgument(0));

        Certificate result = service.unrevoke("02b3c4");

        assertThat(result.getStatus()).isEqualTo(CertificateStatus.GOOD);
        assertThat(result.getRevocationReason()).isNull();
        assertThat(result.getRevocationTime()).isNull();
        assertThat(result.getSequence()).isEqualTo(3L);
        verify(certRepository, times(1)).save(cert);
    }
}
