package com.catsitter.api.testutil;

import com.catsitter.api.entity.*;
import com.catsitter.api.entity.enums.*;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public class TestDataFactory {

    public static Account createAccount(TestEntityManager em, String email) {
        Account account = new Account();
        account.setEmail(email);
        account.setOauthProvider(OAuthProvider.LOCAL);
        account.setStatus(AccountStatus.ACTIVE);
        return em.persist(account);
    }

    public static Profile createProfile(TestEntityManager em, Account account, RoleType role, String name) {
        Profile profile = new Profile();
        profile.setAccount(account);
        profile.setRoleType(role);
        profile.setName(name);
        return em.persist(profile);
    }

    public static Service createService(TestEntityManager em, Profile sitter, String name, BigDecimal price) {
        Service service = new Service();
        service.setSitterProfile(sitter);
        service.setName(name);
        service.setBasePrice(price);
        service.setDurationMinutes(30);
        service.setSupportedPetTypes(List.of("CAT"));
        return em.persist(service);
    }

    public static Order createOrder(TestEntityManager em, Profile client, Profile sitter, Service service) {
        Order order = new Order();
        order.setClientProfile(client);
        order.setCurrentSitter(sitter);
        order.setService(service);
        order.setBaseAmount(service.getBasePrice());
        order.setTotalAmount(service.getBasePrice());
        order.setOrderStatus(OrderStatus.PENDING);
        order.setPaymentStatus(PaymentStatus.UNPAID);
        order.setQuestionnaireStatus(QuestionnaireStatus.NOT_REQUIRED);
        return em.persist(order);
    }

    public static Visit createVisit(TestEntityManager em, Order order) {
        Visit visit = new Visit();
        visit.setOrder(order);
        visit.setVisitStartTime(OffsetDateTime.now());
        visit.setVisitEndTime(OffsetDateTime.now().plusHours(1));
        visit.setStatus(VisitStatus.SCHEDULED);
        return em.persist(visit);
    }

    public static VisitService createVisitService(TestEntityManager em, Visit visit, ServiceType type, String desc) {
        VisitService service = new VisitService();
        service.setVisit(visit);
        service.setServiceType(type);
        service.setDescription(desc);
        service.setSortOrder(0);
        service.setIsCompleted(false);
        return em.persist(service);
    }
}
