package com.catsitter.api.service;

import com.catsitter.api.entity.SitterSubscription;
import com.catsitter.api.repository.SitterSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Component
public class SubscriptionRenewalJob {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionRenewalJob.class);

    private final SitterSubscriptionRepository subscriptionRepository;
    private final ResendService resendService;

    @org.springframework.beans.factory.annotation.Value("${application.subscription.notification-days}")
    private Integer globalNotificationDays;

    public SubscriptionRenewalJob(SitterSubscriptionRepository subscriptionRepository, ResendService resendService) {
        this.subscriptionRepository = subscriptionRepository;
        this.resendService = resendService;
    }

    /**
     * Run every day at 00:00 server time
     */
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional(readOnly = true)
    public void sendRenewalReminders() {
        logger.info("[JOB START] Subscription renewal reminder job");
        try {
            LocalDate today = LocalDate.now();
            
            // Find subscriptions ending in 1-30 days to check against sitter preference
            List<SitterSubscription> upcomingExpirations = subscriptionRepository.findByEndDateBetweenAndStatus(
                    today.plusDays(1), today.plusDays(31), "ACTIVE");

            int count = 0;
            for (SitterSubscription sub : upcomingExpirations) {
                LocalDate targetDate = sub.getEndDate().minusDays(globalNotificationDays);
                
                if (targetDate.equals(today)) {
                    sendEmail(sub);
                    count++;
                }
            }
            logger.info("[JOB END] Subscription renewal reminder job - Sent: {}", count);
        } catch (Exception e) {
            logger.error("[JOB ERROR] Subscription renewal reminder job - Reason: {}", e.getMessage(), e);
        }
    }

    private void sendEmail(SitterSubscription sub) {
        String to = sub.getSitterProfile().getAccount().getEmail();
        String sitterName = sub.getSitterProfile().getName();
        String planName = sub.getPlan().getName();
        String expiryDate = sub.getEndDate().toString();
        
        // Payment link points to the subscription page or a direct checkout link
        String paymentLink = "https://catsitter.example/v1/payments/subscriptions/pay?planId=" + sub.getPlan().getId();

        String subject = "[貓咪保母] 您的訂閱方案即將到期";
        String htmlContent = String.format(
                "<h1>您好 %s,</h1>" +
                "<p>您的 <strong>%s</strong> 訂閱方案將於 <strong>%s</strong> 到期。</p>" +
                "<p>為了避免接單功能中斷，請點擊下方連結完成續訂：</p>" +
                "<a href='%s' style='background: #f59e0b; color: white; padding: 10px 20px; text-decoration: none; border-radius: 5px; display: inline-block;'>立即續訂</a>" +
                "<p>祝您接單順利！</p>",
                sitterName, planName, expiryDate, paymentLink
        );

        resendService.sendEmail(to, subject, htmlContent);
        logger.info("Sent renewal reminder to {}", to);
    }
}
