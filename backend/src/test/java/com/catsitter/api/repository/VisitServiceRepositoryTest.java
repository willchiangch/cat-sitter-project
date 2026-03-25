package com.catsitter.api.repository;

import com.catsitter.api.entity.*;
import com.catsitter.api.entity.enums.*;
import com.catsitter.api.testutil.TestDataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(VisitServiceRepositoryTest.AuditConfig.class)
class VisitServiceRepositoryTest {

    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.data.jpa.repository.config.EnableJpaAuditing(dateTimeProviderRef = "dateTimeProvider")
    static class AuditConfig {
        @org.springframework.context.annotation.Bean
        public org.springframework.data.auditing.DateTimeProvider dateTimeProvider() {
            return () -> java.util.Optional.of(java.time.OffsetDateTime.now());
        }
    }

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private VisitServiceRepository visitServiceRepository;

    @Test
    void shouldSaveAndFindVisitService() {
        // Use TestDataFactory to simplify setup
        Account account = TestDataFactory.createAccount(entityManager, "test@example.com");
        Profile client = TestDataFactory.createProfile(entityManager, account, RoleType.CLIENT, "Test Client");
        Profile sitter = TestDataFactory.createProfile(entityManager, account, RoleType.SITTER, "Test Sitter");
        Service plan = TestDataFactory.createService(entityManager, sitter, "Standard Plan", new BigDecimal("500.00"));
        Order order = TestDataFactory.createOrder(entityManager, client, sitter, plan);
        Visit visit = TestDataFactory.createVisit(entityManager, order);

        // The one we are testing
        VisitService service = new VisitService();
        service.setVisit(visit);
        service.setServiceType(ServiceType.FEEDING);
        service.setDescription("Feed the cat");
        service.setSortOrder(0);
        service.setIsCompleted(false);

        visitServiceRepository.save(service);
        entityManager.flush();
        entityManager.clear();

        List<VisitService> results = visitServiceRepository.findByVisitIdOrderBySortOrderAsc(visit.getId());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getServiceType()).isEqualTo(ServiceType.FEEDING);
        assertThat(results.get(0).getDescription()).isEqualTo("Feed the cat");
    }
}

