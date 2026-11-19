package com.company.incert.kms.service;

import com.company.incert.kms.dto.CreateCaRequest;
import com.company.incert.kms.dto.IssueCertificateRequest;
import com.company.incert.kms.dto.IssuedCertificateDto;
import com.company.incert.kms.entity.Certificate;
import com.company.incert.kms.entity.CertificateAuthority;
import com.company.incert.kms.entity.CertificateStatus;
import com.company.incert.kms.repository.CertificateAuthorityRepository;
import com.company.incert.kms.repository.CertificateRepository;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

/**
 * Creates certificate authorities and issues certificates from them.
 * <p>
 * Nothing is seeded: every CA and certificate originates from an explicit API call. Each issued certificate
 * is stored in the inventory with status GOOD; the persistence listener emits the outbox event so the
 * OCSP responder learns about it.
 */
@Slf4j
@Service
public class CertificateAuthorityService {

    private static final int DEFAULT_ROOT_VALIDITY_DAYS = 3650;
    private static final int DEFAULT_SUBORDINATE_VALIDITY_DAYS = 1825;
    private static final int DEFAULT_CERT_VALIDITY_DAYS = 365;
    private static final int MAX_NAME_LENGTH = 128;

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final CertificateAuthorityRepository caRepository;
    private final CertificateRepository certRepository;
    private final String ocspResponderUrl;
    private final SecureRandom random = new SecureRandom();

    public CertificateAuthorityService(
            CertificateAuthorityRepository caRepository,
            CertificateRepository certRepository,
            @Value("${kms.ocsp.responder-url:http://localhost:8080/ocsp}") String ocspResponderUrl) {
        this.caRepository = caRepository;
        this.certRepository = certRepository;
        this.ocspResponderUrl = ocspResponderUrl;
    }

    @Transactional(readOnly = true)
    public List<CertificateAuthority> listAll() {
        return caRepository.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public CertificateAuthority get(Long id) {
        return caRepository.findById(id)
                .orElseThrow(() -> new CertificateAuthorityNotFoundException("Certificate authority not found: " + id));
    }

    /** PEM chain for the CA: its certificate followed by its issuers up to the root. */
    @Transactional(readOnly = true)
    public String chainPem(CertificateAuthority ca) {
        StringBuilder chain = new StringBuilder();
        CertificateAuthority current = ca;
        int guard = 0;
        while (current != null && guard++ < 16) {
            chain.append(current.getCertificatePem());
            current = current.getParentId() == null ? null : caRepository.findById(current.getParentId()).orElse(null);
        }
        return chain.toString();
    }

    @Transactional
    public CertificateAuthority createCa(CreateCaRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        String name = requireText(request.name(), "name");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (caRepository.existsByName(name)) {
            throw new IllegalArgumentException("A certificate authority named '" + name + "' already exists");
        }
        X500Name subject = parseDn(request.subjectDn(), "subjectDn");
        String keyAlgorithm = normalizeKeyAlgorithm(request.keyAlgorithm());
        int keySize = normalizeKeySize(keyAlgorithm, request.keySize());

        CertificateAuthority parent = request.parentCaId() == null ? null : get(request.parentCaId());
        int defaultValidity = parent == null ? DEFAULT_ROOT_VALIDITY_DAYS : DEFAULT_SUBORDINATE_VALIDITY_DAYS;
        Instant notBefore = Instant.now().minus(5, ChronoUnit.MINUTES);
        Instant notAfter = resolveNotAfter(request.validityDays(), defaultValidity, parent);

        try {
            KeyPair keyPair = generateKeyPair(keyAlgorithm, keySize);
            SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());
            JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();

            // Algorithm this CA will use when it signs (derived from its own key).
            String ownSignatureAlgorithm = signatureAlgorithmFor(keyAlgorithm, keySize);

            X500Name issuerName;
            PrivateKey issuerKey;
            SubjectPublicKeyInfo issuerSpki;
            String certificateSignatureAlgorithm;
            if (parent == null) {
                issuerName = subject;
                issuerKey = keyPair.getPrivate();
                issuerSpki = spki;
                certificateSignatureAlgorithm = ownSignatureAlgorithm;
            } else {
                X509CertificateHolder parentCert = parseCertificate(parent.getCertificatePem());
                issuerName = parentCert.getSubject();
                issuerKey = parsePrivateKey(parent.getPrivateKeyPem());
                issuerSpki = parentCert.getSubjectPublicKeyInfo();
                certificateSignatureAlgorithm = parent.getSignatureAlgorithm();
            }

            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                    issuerName, newSerial(), Date.from(notBefore), Date.from(notAfter), subject, spki);
            builder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(spki));
            builder.addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(issuerSpki));
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

            ContentSigner signer = new JcaContentSignerBuilder(certificateSignatureAlgorithm)
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(issuerKey);
            X509CertificateHolder certificate = builder.build(signer);

            CertificateAuthority saved = caRepository.save(CertificateAuthority.builder()
                    .name(name)
                    .subjectDn(subject.toString())
                    .parentId(parent == null ? null : parent.getId())
                    .keyAlgorithm(keyAlgorithm)
                    .signatureAlgorithm(ownSignatureAlgorithm)
                    .issuerKeyHashHex(keyHash(certificate))
                    .issuerNameHashHex(nameHash(certificate))
                    .certificatePem(toPem("CERTIFICATE", certificate.getEncoded()))
                    .privateKeyPem(toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()))
                    .notBefore(certificate.getNotBefore().toInstant())
                    .notAfter(certificate.getNotAfter().toInstant())
                    .build());
            log.info("Created {} CA id={} name='{}' subject='{}' keyAlgorithm={}",
                    parent == null ? "root" : "subordinate", saved.getId(), name, saved.getSubjectDn(), keyAlgorithm);
            return saved;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create certificate authority: " + e.getMessage(), e);
        }
    }

    @Transactional
    public IssuedCertificateDto issue(Long caId, IssueCertificateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        CertificateAuthority ca = get(caId);
        CertificateProfile profile = CertificateProfile.parse(request.profile());
        boolean hasCsr = request.csrPem() != null && !request.csrPem().isBlank();
        boolean hasSubject = request.subjectDn() != null && !request.subjectDn().isBlank();
        if (!hasCsr && !hasSubject) {
            throw new IllegalArgumentException("Provide either csrPem, or subjectDn to have the KMS generate the key pair");
        }

        try {
            X500Name subject;
            SubjectPublicKeyInfo subjectKeyInfo;
            Extensions requestedExtensions = null;
            String generatedPrivateKeyPem = null;

            if (hasCsr) {
                PKCS10CertificationRequest csr = parseCsr(request.csrPem());
                if (!csr.isSignatureValid(new JcaContentVerifierProviderBuilder()
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(csr.getSubjectPublicKeyInfo()))) {
                    throw new IllegalArgumentException("CSR signature is invalid (proof of possession failed)");
                }
                subject = csr.getSubject();
                if (subject.getRDNs().length == 0) {
                    throw new IllegalArgumentException("CSR has an empty subject DN");
                }
                subjectKeyInfo = csr.getSubjectPublicKeyInfo();
                requestedExtensions = requestedExtensions(csr);
            } else {
                subject = parseDn(request.subjectDn(), "subjectDn");
                String keyAlgorithm = normalizeKeyAlgorithm(request.keyAlgorithm());
                int keySize = normalizeKeySize(keyAlgorithm, request.keySize());
                KeyPair keyPair = generateKeyPair(keyAlgorithm, keySize);
                subjectKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());
                generatedPrivateKeyPem = toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
            }

            X509CertificateHolder caCert = parseCertificate(ca.getCertificatePem());
            PrivateKey caKey = parsePrivateKey(ca.getPrivateKeyPem());
            Instant notBefore = Instant.now().minus(5, ChronoUnit.MINUTES);
            Instant notAfter = resolveNotAfter(request.validityDays(), DEFAULT_CERT_VALIDITY_DAYS, ca);

            JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                    caCert.getSubject(), newSerial(), Date.from(notBefore), Date.from(notAfter), subject, subjectKeyInfo);
            builder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(subjectKeyInfo));
            builder.addExtension(Extension.authorityKeyIdentifier, false,
                    extUtils.createAuthorityKeyIdentifier(caCert.getSubjectPublicKeyInfo()));
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            applyProfile(builder, profile, subjectKeyInfo, requestedExtensions);

            ContentSigner signer = new JcaContentSignerBuilder(ca.getSignatureAlgorithm())
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(caKey);
            X509CertificateHolder issued = builder.build(signer);

            Certificate entity = certRepository.save(Certificate.builder()
                    .serialNumberHex(HexFormat.of().formatHex(issued.getSerialNumber().toByteArray()))
                    .issuerDn(caCert.getSubject().toString())
                    .issuerKeyHashHex(ca.getIssuerKeyHashHex())
                    .issuerNameHashHex(ca.getIssuerNameHashHex())
                    .subjectDn(issued.getSubject().toString())
                    .notBefore(issued.getNotBefore().toInstant())
                    .notAfter(issued.getNotAfter().toInstant())
                    .status(CertificateStatus.GOOD)
                    .pemEncoded(toPem("CERTIFICATE", issued.getEncoded()))
                    .sequence(0L)
                    .caId(ca.getId())
                    .build());
            log.info("Issued {} certificate serial={} subject='{}' from CA id={}",
                    profile, entity.getSerialNumberHex(), entity.getSubjectDn(), ca.getId());

            return new IssuedCertificateDto(
                    ca.getId(),
                    entity.getSerialNumberHex(),
                    entity.getSubjectDn(),
                    entity.getIssuerDn(),
                    profile.name(),
                    entity.getNotBefore(),
                    entity.getNotAfter(),
                    entity.getPemEncoded(),
                    entity.getPemEncoded() + chainPem(ca),
                    generatedPrivateKeyPem);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (PKCSException e) {
            throw new IllegalArgumentException("CSR could not be verified: " + e.getMessage());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue certificate: " + e.getMessage(), e);
        }
    }

    private void applyProfile(X509v3CertificateBuilder builder, CertificateProfile profile,
                              SubjectPublicKeyInfo subjectKeyInfo, Extensions requestedExtensions) throws IOException {
        boolean ecKey = X9ObjectIdentifiers.id_ecPublicKey.equals(subjectKeyInfo.getAlgorithm().getAlgorithm());
        int signatureUsage = KeyUsage.digitalSignature;
        int tlsUsage = ecKey ? KeyUsage.digitalSignature : (KeyUsage.digitalSignature | KeyUsage.keyEncipherment);

        switch (profile) {
            case TLS_SERVER -> {
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(tlsUsage));
                builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
                copySubjectAltName(builder, requestedExtensions);
                addOcspLocation(builder);
            }
            case TLS_CLIENT -> {
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(signatureUsage));
                builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
                copySubjectAltName(builder, requestedExtensions);
                addOcspLocation(builder);
            }
            case OCSP_SIGNING -> {
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(signatureUsage));
                builder.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_OCSPSigning));
                builder.addExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nocheck, false, DERNull.INSTANCE);
            }
            case GENERIC -> {
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(signatureUsage));
                addOcspLocation(builder);
            }
        }
    }

    private void addOcspLocation(X509v3CertificateBuilder builder) throws IOException {
        if (ocspResponderUrl == null || ocspResponderUrl.isBlank()) {
            return;
        }
        AccessDescription ocsp = new AccessDescription(AccessDescription.id_ad_ocsp,
                new GeneralName(GeneralName.uniformResourceIdentifier, ocspResponderUrl.trim()));
        builder.addExtension(Extension.authorityInfoAccess, false, new AuthorityInformationAccess(ocsp));
    }

    private static void copySubjectAltName(X509v3CertificateBuilder builder, Extensions requested) throws IOException {
        if (requested == null) {
            return;
        }
        Extension san = requested.getExtension(Extension.subjectAlternativeName);
        if (san != null) {
            builder.addExtension(Extension.subjectAlternativeName, san.isCritical(), san.getParsedValue());
        }
    }

    private static Extensions requestedExtensions(PKCS10CertificationRequest csr) {
        Attribute[] attributes = csr.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest);
        if (attributes == null || attributes.length == 0 || attributes[0].getAttrValues().size() == 0) {
            return null;
        }
        ASN1Encodable value = attributes[0].getAttrValues().getObjectAt(0);
        return Extensions.getInstance(value);
    }

    private Instant resolveNotAfter(Integer requestedDays, int defaultDays, CertificateAuthority issuer) {
        if (requestedDays != null && requestedDays <= 0) {
            throw new IllegalArgumentException("validityDays must be positive");
        }
        Instant notAfter = Instant.now().plus(requestedDays != null ? requestedDays : defaultDays, ChronoUnit.DAYS);
        if (issuer != null && notAfter.isAfter(issuer.getNotAfter())) {
            if (requestedDays != null) {
                throw new IllegalArgumentException("Requested validity exceeds the issuing CA validity (CA expires "
                        + issuer.getNotAfter() + ")");
            }
            return issuer.getNotAfter();
        }
        return notAfter;
    }

    private KeyPair generateKeyPair(String keyAlgorithm, int keySize) throws Exception {
        if ("EC".equals(keyAlgorithm)) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
            kpg.initialize(new ECGenParameterSpec(keySize == 384 ? "secp384r1" : "secp256r1"), random);
            return kpg.generateKeyPair();
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(keySize, random);
        return kpg.generateKeyPair();
    }

    private static String signatureAlgorithmFor(String keyAlgorithm, int keySize) {
        if ("EC".equals(keyAlgorithm)) {
            return keySize == 384 ? "SHA384withECDSA" : "SHA256withECDSA";
        }
        return "SHA256withRSA";
    }

    private static String normalizeKeyAlgorithm(String value) {
        if (value == null || value.isBlank()) {
            return "RSA";
        }
        String upper = value.trim().toUpperCase();
        if ("ECDSA".equals(upper)) {
            upper = "EC";
        }
        if (!"RSA".equals(upper) && !"EC".equals(upper)) {
            throw new IllegalArgumentException("keyAlgorithm must be RSA or EC");
        }
        return upper;
    }

    private static int normalizeKeySize(String keyAlgorithm, Integer keySize) {
        if ("EC".equals(keyAlgorithm)) {
            int size = keySize == null ? 256 : keySize;
            if (size != 256 && size != 384) {
                throw new IllegalArgumentException("EC keySize must be 256 or 384");
            }
            return size;
        }
        int size = keySize == null ? 3072 : keySize;
        if (size != 2048 && size != 3072 && size != 4096) {
            throw new IllegalArgumentException("RSA keySize must be 2048, 3072 or 4096");
        }
        return size;
    }

    private BigInteger newSerial() {
        BigInteger serial;
        do {
            serial = new BigInteger(127, random);
        } while (serial.signum() == 0);
        return serial;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static X500Name parseDn(String dn, String field) {
        String text = requireText(dn, field);
        try {
            return new X500Name(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid " + field + " '" + text + "': " + e.getMessage());
        }
    }

    private static PKCS10CertificationRequest parseCsr(String pem) throws IOException {
        try (PEMParser parser = new PEMParser(new StringReader(pem.trim()))) {
            Object object = parser.readObject();
            if (object instanceof PKCS10CertificationRequest csr) {
                return csr;
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("csrPem is not a valid PEM PKCS#10 certificate request: " + e.getMessage());
        }
        throw new IllegalArgumentException("csrPem is not a valid PEM PKCS#10 certificate request");
    }

    private static X509CertificateHolder parseCertificate(String pem) throws IOException {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object object = parser.readObject();
            if (object instanceof X509CertificateHolder holder) {
                return holder;
            }
        }
        throw new IllegalStateException("Stored CA certificate is not a valid PEM certificate");
    }

    private static PrivateKey parsePrivateKey(String pem) throws IOException {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object object = parser.readObject();
            if (object instanceof PrivateKeyInfo info) {
                return new JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getPrivateKey(info);
            }
        }
        throw new IllegalStateException("Stored CA private key is not a valid PKCS#8 PEM");
    }

    private static String toPem(String type, byte[] der) throws IOException {
        StringWriter out = new StringWriter();
        try (PemWriter writer = new PemWriter(out)) {
            writer.writeObject(new PemObject(type, der));
        }
        return out.toString();
    }

    private static String keyHash(X509CertificateHolder certificate) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(certificate.getSubjectPublicKeyInfo().getPublicKeyData().getBytes()));
    }

    private static String nameHash(X509CertificateHolder certificate) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(certificate.getSubject().getEncoded()));
    }

    public static class CertificateAuthorityNotFoundException extends RuntimeException {
        public CertificateAuthorityNotFoundException(String message) {
            super(message);
        }
    }
}
