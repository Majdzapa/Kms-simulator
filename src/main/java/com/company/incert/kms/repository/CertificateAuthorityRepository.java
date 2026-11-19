package com.company.incert.kms.repository;

import com.company.incert.kms.entity.CertificateAuthority;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CertificateAuthorityRepository extends JpaRepository<CertificateAuthority, Long> {

    boolean existsByName(String name);

    List<CertificateAuthority> findAllByOrderByIdAsc();
}

