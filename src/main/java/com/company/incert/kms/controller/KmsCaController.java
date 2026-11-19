package com.company.incert.kms.controller;

import com.company.incert.kms.dto.CaDto;
import com.company.incert.kms.dto.CreateCaRequest;
import com.company.incert.kms.dto.IssueCertificateRequest;
import com.company.incert.kms.dto.IssuedCertificateDto;
import com.company.incert.kms.entity.CertificateAuthority;
import com.company.incert.kms.service.CertificateAuthorityService;
import com.company.incert.kms.service.CertificateAuthorityService.CertificateAuthorityNotFoundException;
import com.company.incert.kms.service.OutboxRelayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Certificate authority management and certificate issuance.
 * The KMS holds no pre-created CA: create one here, then issue certificates (from a CSR or with a generated key).
 */
@Slf4j
@RestController
@RequestMapping("/kms/cas")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "KMS Certificate Authorities", description = "Create CAs and issue certificates from CSRs")
public class KmsCaController {

    private final CertificateAuthorityService caService;
    private final OutboxRelayService outboxRelay;

    @Operation(summary = "List certificate authorities")
    @GetMapping
    public List<CaDto> list() {
        return caService.listAll().stream().map(CaDto::from).toList();
    }

    @Operation(summary = "Get a certificate authority")
    @GetMapping("/{id}")
    public CaDto get(@PathVariable("id") Long id) {
        return CaDto.from(caService.get(id));
    }

    @Operation(summary = "Create a CA", description = "Creates a self-signed root CA, or a subordinate CA when parentCaId is set")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "CA created"),
            @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    @PostMapping
    public ResponseEntity<CaDto> create(@RequestBody CreateCaRequest request) {
        CertificateAuthority ca = caService.createCa(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(CaDto.from(ca));
    }

    @Operation(summary = "Download the CA certificate (PEM)")
    @GetMapping(value = "/{id}/certificate", produces = "application/x-pem-file")
    public ResponseEntity<String> certificate(@PathVariable("id") Long id,
                                              @RequestParam(value = "chain", defaultValue = "false") boolean chain) {
        CertificateAuthority ca = caService.get(id);
        String pem = chain ? caService.chainPem(ca) : ca.getCertificatePem();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ca-" + id + ".pem\"")
                .contentType(MediaType.parseMediaType("application/x-pem-file"))
                .body(pem);
    }

    @Operation(summary = "Issue a certificate",
            description = "Signs the supplied PKCS#10 CSR, or generates a key pair when only subjectDn is given. "
                    + "The certificate is stored as GOOD and published to the OCSP responder through the outbox.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Certificate issued"),
            @ApiResponse(responseCode = "400", description = "Invalid CSR or parameters"),
            @ApiResponse(responseCode = "404", description = "CA not found")
    })
    @PostMapping("/{id}/certificates")
    public ResponseEntity<IssuedCertificateDto> issue(@PathVariable("id") Long id,
                                                      @RequestBody IssueCertificateRequest request) {
        IssuedCertificateDto issued = caService.issue(id, request);
        outboxRelay.relay();
        return ResponseEntity.status(HttpStatus.CREATED).body(issued);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CertificateAuthorityNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(CertificateAuthorityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}

