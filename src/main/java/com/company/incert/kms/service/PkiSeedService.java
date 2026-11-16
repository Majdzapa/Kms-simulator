package com.company.incert.kms.service;

import com.company.incert.kms.entity.Certificate;
import com.company.incert.kms.entity.CertificateStatus;
import com.company.incert.kms.entity.RevocationReason;
import com.company.incert.kms.repository.CertificateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Seeds the KMS database on startup if the certificate table is empty.
 * Reuses existing PKI artifacts in ./data/pki/ (root-ca.pem, intermediate-ca-cert.pem,
 * intermediate-ca-key.pem.TESTONLY, ee-01.pem..ee-05.pem) when available so that
 * KMS, SoftHSM2, OCSP Responder, and OCSP Client share the exact same CA hierarchy.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PkiSeedService {

    private final CertificateRepository certRepository;

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Bean
    public ApplicationRunner pkiSeeder() {
        return args -> {
            if (certRepository.count() > 0) {
                log.info("PKI already seeded ({} certs), skipping", certRepository.count());
                return;
            }
            log.info("Seeding test PKI...");
            seedPki();
            log.info("PKI seeding complete: {} certificates", certRepository.count());
        };
    }

    @Transactional
    public void seedPki() throws Exception {
        Path pkiDir = Paths.get("./data/pki");
        Path intCertPath = pkiDir.resolve("intermediate-ca-cert.pem");
        Path intKeyPath = pkiDir.resolve("intermediate-ca-key.pem.TESTONLY");

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(3072);
        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();

        X509Certificate intCert;
        PrivateKey intPrivKey;
        X509CertificateHolder intCertHolder;

        if (Files.exists(intCertPath) && Files.exists(intKeyPath)) {
            log.info("Loading existing Intermediate CA from {}", intCertPath.toAbsolutePath());
            intCert = loadCert(intCertPath);
            intPrivKey = loadPrivateKey(intKeyPath);
            intCertHolder = new X509CertificateHolder(intCert.getEncoded());
        } else {
            log.info("Generating new Root CA and Intermediate CA...");
            KeyPair rootKp = kpg.generateKeyPair();
            KeyPair intKp = kpg.generateKeyPair();

            X500Name rootName = new X500Name("CN=Incert Test Root CA,O=Incert,C=CH");
            Date rootNotBefore = Date.from(Instant.now().minus(1, ChronoUnit.DAYS));
            Date rootNotAfter  = Date.from(Instant.now().plus(3650, ChronoUnit.DAYS));
            BigInteger rootSerial = BigInteger.probablePrime(128, new Random());

            ContentSigner rootSigner = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider("BC").build(rootKp.getPrivate());

            X509v3CertificateBuilder rootBuilder = new X509v3CertificateBuilder(
                    rootName, rootSerial, rootNotBefore, rootNotAfter, rootName,
                    SubjectPublicKeyInfo.getInstance(rootKp.getPublic().getEncoded())
            );
            rootBuilder.addExtension(Extension.subjectKeyIdentifier,  false, extUtils.createSubjectKeyIdentifier(rootKp.getPublic()));
            rootBuilder.addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(rootKp.getPublic()));
            rootBuilder.addExtension(Extension.basicConstraints, true,  new BasicConstraints(true));
            rootBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

            X509CertificateHolder rootCertHolder = rootBuilder.build(rootSigner);
            X509Certificate rootCert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(rootCertHolder);

            X500Name intName = new X500Name("CN=Incert Test Intermediate CA,O=Incert,C=CH");
            Date intNotBefore = Date.from(Instant.now().minus(1, ChronoUnit.DAYS));
            Date intNotAfter  = Date.from(Instant.now().plus(1825, ChronoUnit.DAYS));
            BigInteger intSerial = BigInteger.probablePrime(128, new Random());

            X509v3CertificateBuilder intBuilder = new X509v3CertificateBuilder(
                    rootName, intSerial, intNotBefore, intNotAfter, intName,
                    SubjectPublicKeyInfo.getInstance(intKp.getPublic().getEncoded())
            );
            intBuilder.addExtension(Extension.subjectKeyIdentifier,   false, extUtils.createSubjectKeyIdentifier(intKp.getPublic()));
            intBuilder.addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(rootKp.getPublic()));
            intBuilder.addExtension(Extension.basicConstraints, true,  new BasicConstraints(0));
            intBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

            intCertHolder = intBuilder.build(rootSigner);
            intCert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(intCertHolder);
            intPrivKey = intKp.getPrivate();

            Files.createDirectories(pkiDir);
            writeFile(pkiDir.resolve("root-ca.pem"), toPem(rootCert));
            writeFile(intCertPath, toPem(intCert));
            writeFile(pkiDir.resolve("ca-chain.pem"), toPem(intCert) + toPem(rootCert));
        }

        X500Name intName = intCertHolder.getSubject();
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        String issuerKeyHashHex = HexFormat.of().formatHex(
                sha1.digest(intCertHolder.getSubjectPublicKeyInfo().getPublicKeyData().getBytes()));
        String issuerNameHashHex = HexFormat.of().formatHex(
                sha1.digest(intCertHolder.getSubject().getEncoded()));

        log.info("Intermediate CA: subject='{}' keyHash={} nameHash={}",
                intName, issuerKeyHashHex, issuerNameHashHex);

        ContentSigner intSigner = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(intPrivKey);

        List<Certificate> certsToSave = new ArrayList<>();

        // First import existing ee-01.pem..ee-05.pem if present on disk
        int importedCount = 0;
        for (int i = 1; i <= 5; i++) {
            Path eePath = pkiDir.resolve(String.format("ee-%02d.pem", i));
            if (Files.exists(eePath)) {
                X509Certificate eeCert = loadCert(eePath);
                String serialHex = HexFormat.of().formatHex(eeCert.getSerialNumber().toByteArray());
                CertificateStatus status = (i == 5) ? CertificateStatus.REVOKED : CertificateStatus.GOOD;
                RevocationReason reason = (i == 5) ? RevocationReason.KEY_COMPROMISE : null;
                Instant revTime = (i == 5) ? Instant.now().minus(2, ChronoUnit.DAYS) : null;

                certsToSave.add(Certificate.builder()
                        .serialNumberHex(serialHex)
                        .issuerDn(intName.toString())
                        .issuerKeyHashHex(issuerKeyHashHex)
                        .issuerNameHashHex(issuerNameHashHex)
                        .subjectDn(eeCert.getSubjectX500Principal().getName())
                        .notBefore(eeCert.getNotBefore().toInstant())
                        .notAfter(eeCert.getNotAfter().toInstant())
                        .status(status)
                        .revocationTime(revTime)
                        .revocationReason(reason)
                        .pemEncoded(toPem(eeCert))
                        .sequence(0L)
                        .build());
                importedCount++;
            }
        }

        // Generate remaining certs up to 50
        for (int i = importedCount + 1; i <= 50; i++) {
            KeyPair eeKp = kpg.generateKeyPair();
            BigInteger eeSerial = BigInteger.probablePrime(128, new Random());
            String serialHex = HexFormat.of().formatHex(eeSerial.toByteArray());

            X500Name eeSubject = new X500Name(String.format("CN=service-%03d.incert.internal,O=Incert,C=CH", i));
            Date eeNotBefore = Date.from(Instant.now().minus(30, ChronoUnit.DAYS));
            Date eeNotAfter  = Date.from(Instant.now().plus(365, ChronoUnit.DAYS));

            X509v3CertificateBuilder eeBuilder = new X509v3CertificateBuilder(
                    intName, eeSerial, eeNotBefore, eeNotAfter, eeSubject,
                    SubjectPublicKeyInfo.getInstance(eeKp.getPublic().getEncoded())
            );
            eeBuilder.addExtension(Extension.subjectKeyIdentifier,   false, extUtils.createSubjectKeyIdentifier(eeKp.getPublic()));
            eeBuilder.addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(intCert.getPublicKey()));
            eeBuilder.addExtension(Extension.basicConstraints, true,  new BasicConstraints(false));
            eeBuilder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

            GeneralName ocspLocation = new GeneralName(GeneralName.uniformResourceIdentifier,
                    "http://localhost:8080/ocsp");
            AccessDescription aiaOcsp = new AccessDescription(AccessDescription.id_ad_ocsp, ocspLocation);
            AuthorityInformationAccess aia = new AuthorityInformationAccess(aiaOcsp);
            eeBuilder.addExtension(Extension.authorityInfoAccess, false, aia);

            X509CertificateHolder eeCertHolder = eeBuilder.build(intSigner);
            X509Certificate eeCert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(eeCertHolder);

            CertificateStatus status = (i <= 9) ? CertificateStatus.REVOKED : CertificateStatus.GOOD;
            Instant revTime = (status == CertificateStatus.REVOKED)
                    ? Instant.now().minus(i, ChronoUnit.DAYS) : null;
            RevocationReason reason = (status == CertificateStatus.REVOKED)
                    ? RevocationReason.KEY_COMPROMISE : null;

            certsToSave.add(Certificate.builder()
                    .serialNumberHex(serialHex)
                    .issuerDn(intName.toString())
                    .issuerKeyHashHex(issuerKeyHashHex)
                    .issuerNameHashHex(issuerNameHashHex)
                    .subjectDn(eeSubject.toString())
                    .notBefore(eeNotBefore.toInstant())
                    .notAfter(eeNotAfter.toInstant())
                    .status(status)
                    .revocationTime(revTime)
                    .revocationReason(reason)
                    .pemEncoded(toPem(eeCert))
                    .sequence(0L)
                    .build());
        }

        for (Certificate cert : certsToSave) {
            certRepository.save(cert);
        }
    }

    private X509Certificate loadCert(Path path) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (InputStream is = Files.newInputStream(path)) {
            return (X509Certificate) cf.generateCertificate(is);
        }
    }

    private PrivateKey loadPrivateKey(Path path) throws Exception {
        try (Reader reader = Files.newBufferedReader(path);
             PEMParser pemParser = new PEMParser(reader)) {
            Object obj = pemParser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            if (obj instanceof PEMKeyPair kp) {
                return converter.getKeyPair(kp).getPrivate();
            } else if (obj instanceof PrivateKeyInfo pki) {
                return converter.getPrivateKey(pki);
            }
            throw new IllegalArgumentException("Unsupported private key format in " + path);
        }
    }

    private static String toPem(Object obj) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter pw = new JcaPEMWriter(sw)) {
            pw.writeObject(obj);
        }
        return sw.toString();
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
