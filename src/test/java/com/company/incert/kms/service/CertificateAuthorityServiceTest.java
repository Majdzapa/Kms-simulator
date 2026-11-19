package com.company.incert.kms.service;

import com.company.incert.kms.dto.CreateCaRequest;
import com.company.incert.kms.dto.IssueCertificateRequest;
import com.company.incert.kms.dto.IssuedCertificateDto;
import com.company.incert.kms.entity.Certificate;
import com.company.incert.kms.entity.CertificateAuthority;
import com.company.incert.kms.entity.CertificateStatus;
import com.company.incert.kms.repository.CertificateAuthorityRepository;
import com.company.incert.kms.repository.CertificateRepository;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CertificateAuthorityServiceTest {

    private final Map<Long, CertificateAuthority> cas = new HashMap<>();
    private final Map<String, Certificate> certs = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    private CertificateAuthorityRepository caRepository;
    private CertificateRepository certRepository;
    private CertificateAuthorityService service;

    @BeforeEach
    void setUp() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        caRepository = mock(CertificateAuthorityRepository.class);
        certRepository = mock(CertificateRepository.class);

        when(caRepository.save(any(CertificateAuthority.class))).thenAnswer(inv -> {
            CertificateAuthority ca = inv.getArgument(0);
            ca.setId(ids.getAndIncrement());
            cas.put(ca.getId(), ca);
            return ca;
        });
        when(caRepository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(cas.get(inv.<Long>getArgument(0))));
        when(caRepository.existsByName(anyString())).thenAnswer(inv ->
                cas.values().stream().anyMatch(c -> c.getName().equals(inv.<String>getArgument(0))));
        when(certRepository.save(any(Certificate.class))).thenAnswer(inv -> {
            Certificate c = inv.getArgument(0);
            certs.put(c.getSerialNumberHex(), c);
            return c;
        });

        service = new CertificateAuthorityService(caRepository, certRepository, "http://ocsp.test/ocsp");
    }

    private CreateCaRequest rootRequest(String name) {
        return new CreateCaRequest(name, "CN=" + name + ",O=Test,C=CH", "RSA", 2048, 365, null);
    }

    @Test
    void createsSelfSignedRootCaWithMatchingRfc6960Hashes() throws Exception {
        CertificateAuthority ca = service.createCa(rootRequest("Root A"));

        X509CertificateHolder cert = parseCert(ca.getCertificatePem());
        assertThat(cert.getSubject()).isEqualTo(cert.getIssuer());
        assertThat(cert.isSignatureValid(new JcaContentVerifierProviderBuilder().setProvider("BC")
                .build(cert.getSubjectPublicKeyInfo()))).isTrue();
        assertThat(BasicConstraints.fromExtensions(cert.getExtensions()).isCA()).isTrue();
        assertThat(ca.getParentId()).isNull();
        assertThat(ca.getPrivateKeyPem()).contains("BEGIN PRIVATE KEY");

        assertThat(ca.getIssuerNameHashHex()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest(cert.getSubject().getEncoded())));
        assertThat(ca.getIssuerKeyHashHex()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest(cert.getSubjectPublicKeyInfo().getPublicKeyData().getBytes())));
    }

    @Test
    void createsSubordinateCaSignedByParent() throws Exception {
        CertificateAuthority root = service.createCa(rootRequest("Root B"));
        CertificateAuthority sub = service.createCa(
                new CreateCaRequest("Sub B", "CN=Sub B,O=Test,C=CH", "EC", 256, 30, root.getId()));

        X509CertificateHolder subCert = parseCert(sub.getCertificatePem());
        X509CertificateHolder rootCert = parseCert(root.getCertificatePem());
        assertThat(subCert.getIssuer()).isEqualTo(rootCert.getSubject());
        assertThat(subCert.isSignatureValid(new JcaContentVerifierProviderBuilder().setProvider("BC")
                .build(rootCert.getSubjectPublicKeyInfo()))).isTrue();
        assertThat(sub.getParentId()).isEqualTo(root.getId());
        assertThat(sub.getSignatureAlgorithm()).isEqualTo("SHA256withECDSA");
        assertThat(service.chainPem(sub)).contains(sub.getCertificatePem()).contains(root.getCertificatePem());
    }

    @Test
    void rejectsDuplicateCaNameAndBadInput() {
        service.createCa(rootRequest("Dup"));
        assertThatThrownBy(() -> service.createCa(rootRequest("Dup")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
        assertThatThrownBy(() -> service.createCa(new CreateCaRequest("X", "not a dn", "RSA", 2048, 10, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subjectDn");
        assertThatThrownBy(() -> service.createCa(new CreateCaRequest("Y", "CN=Y", "RSA", 1024, 10, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("keySize");
        assertThatThrownBy(() -> service.createCa(new CreateCaRequest(" ", "CN=Z", "RSA", 2048, 10, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
    }

    @Test
    void issuesOcspSigningCertificateFromCsrAndStoresGoodInventoryEntry() throws Exception {
        CertificateAuthority ca = service.createCa(rootRequest("Root C"));
        KeyPair responderKey = rsaKeyPair();
        String csr = csrPem("CN=Responder,O=Test,C=CH", responderKey, responderKey);

        IssuedCertificateDto issued = service.issue(ca.getId(),
                new IssueCertificateRequest(csr, null, null, null, 30, "OCSP_SIGNING"));

        X509CertificateHolder cert = parseCert(issued.certificatePem());
        X509CertificateHolder caCert = parseCert(ca.getCertificatePem());
        assertThat(issued.privateKeyPem()).isNull();
        assertThat(cert.getIssuer()).isEqualTo(caCert.getSubject());
        assertThat(cert.getSubject()).isEqualTo(new X500Name("CN=Responder,O=Test,C=CH"));
        assertThat(cert.isSignatureValid(new JcaContentVerifierProviderBuilder().setProvider("BC")
                .build(caCert.getSubjectPublicKeyInfo()))).isTrue();
        assertThat(ExtendedKeyUsage.fromExtensions(cert.getExtensions()).hasKeyPurposeId(KeyPurposeId.id_kp_OCSPSigning)).isTrue();
        assertThat(cert.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nocheck)).isNotNull();
        assertThat(BasicConstraints.fromExtensions(cert.getExtensions()).isCA()).isFalse();
        assertThat(cert.getExtension(Extension.authorityInfoAccess)).as("no AIA on an OCSP signer").isNull();
        assertThat(issued.chainPem()).contains(issued.certificatePem()).contains(ca.getCertificatePem());

        Certificate stored = certs.get(issued.serialNumberHex());
        assertThat(stored).isNotNull();
        assertThat(stored.getStatus()).isEqualTo(CertificateStatus.GOOD);
        assertThat(stored.getSequence()).isZero();
        assertThat(stored.getCaId()).isEqualTo(ca.getId());
        assertThat(stored.getIssuerKeyHashHex()).isEqualTo(ca.getIssuerKeyHashHex());
        assertThat(stored.getIssuerNameHashHex()).isEqualTo(ca.getIssuerNameHashHex());
    }

    @Test
    void issuesTlsServerCertificateWithAiaAndGeneratedKey() throws Exception {
        CertificateAuthority ca = service.createCa(rootRequest("Root D"));

        IssuedCertificateDto issued = service.issue(ca.getId(),
                new IssueCertificateRequest(null, "CN=svc.test,O=Test,C=CH", "EC", 256, null, "TLS_SERVER"));

        X509CertificateHolder cert = parseCert(issued.certificatePem());
        assertThat(issued.privateKeyPem()).contains("BEGIN PRIVATE KEY");
        assertThat(ExtendedKeyUsage.fromExtensions(cert.getExtensions()).hasKeyPurposeId(KeyPurposeId.id_kp_serverAuth)).isTrue();
        assertThat(cert.getExtension(Extension.authorityInfoAccess)).isNotNull();
        assertThat(cert.getNotAfter()).isBeforeOrEqualTo(parseCert(ca.getCertificatePem()).getNotAfter());
    }

    @Test
    void rejectsCsrWithInvalidProofOfPossession() throws Exception {
        CertificateAuthority ca = service.createCa(rootRequest("Root E"));
        KeyPair claimed = rsaKeyPair();
        KeyPair actualSigner = rsaKeyPair();
        String forged = csrPem("CN=Forged,O=Test,C=CH", claimed, actualSigner);

        assertThatThrownBy(() -> service.issue(ca.getId(), new IssueCertificateRequest(forged, null, null, null, 10, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("signature is invalid");
        assertThat(certs).isEmpty();
    }

    @Test
    void rejectsMalformedCsrMissingInputUnknownProfileAndExcessiveValidity() throws Exception {
        CertificateAuthority ca = service.createCa(rootRequest("Root F"));

        assertThatThrownBy(() -> service.issue(ca.getId(), new IssueCertificateRequest("garbage", null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("csrPem");
        assertThatThrownBy(() -> service.issue(ca.getId(), new IssueCertificateRequest(null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("csrPem");
        assertThatThrownBy(() -> service.issue(ca.getId(), new IssueCertificateRequest(null, "CN=a", "EC", 256, null, "WHATEVER")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("profile");
        assertThatThrownBy(() -> service.issue(ca.getId(), new IssueCertificateRequest(null, "CN=a", "EC", 256, 9999, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceeds");
        assertThatThrownBy(() -> service.issue(999L, new IssueCertificateRequest(null, "CN=a", "EC", 256, null, null)))
                .isInstanceOf(CertificateAuthorityService.CertificateAuthorityNotFoundException.class);
    }

    // --- helpers -------------------------------------------------------------------------------

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    /** CSR carrying {@code subjectKey}'s public key, signed with {@code signingKey}'s private key. */
    private static String csrPem(String dn, KeyPair subjectKey, KeyPair signingKey) throws Exception {
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(signingKey.getPrivate());
        PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(new X500Name(dn), subjectKey.getPublic())
                .build(signer);
        StringWriter out = new StringWriter();
        try (PemWriter writer = new PemWriter(out)) {
            writer.writeObject(new PemObject("CERTIFICATE REQUEST", csr.getEncoded()));
        }
        return out.toString();
    }

    private static X509CertificateHolder parseCert(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            return (X509CertificateHolder) parser.readObject();
        }
    }
}

