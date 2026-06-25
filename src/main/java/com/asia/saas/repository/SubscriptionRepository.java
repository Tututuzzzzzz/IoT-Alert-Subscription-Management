package com.asia.saas.repository;

import com.asia.saas.entity.Subscription;
import com.asia.saas.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    Optional<Subscription> findByUser(User user);
    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);
}
