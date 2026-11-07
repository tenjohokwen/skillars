package com.softropic.skillars.platform.payment.service;

import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** skillars-deferred-176 AC5 (RS07c): a retried upgrade whose Stripe item already has the target price sends no update. */
class StripeClientTest {

    private static Subscription withItemPrice(String priceId) {
        Price price = new Price();
        price.setId(priceId);
        SubscriptionItem item = new SubscriptionItem();
        item.setPrice(price);
        SubscriptionItemCollection items = new SubscriptionItemCollection();
        items.setData(List.of(item));
        Subscription subscription = new Subscription();
        subscription.setItems(items);
        return subscription;
    }

    @Test
    void theSamePrice_isAlreadyThere() {
        assertThat(StripeClient.alreadyAtPrice(withItemPrice("price_acad"), "price_acad")).isTrue();
    }

    @Test
    void anotherPrice_orNoItems_isNot() {
        assertThat(StripeClient.alreadyAtPrice(withItemPrice("price_instr"), "price_acad")).isFalse();
        assertThat(StripeClient.alreadyAtPrice(new Subscription(), "price_acad")).isFalse();
    }
}
