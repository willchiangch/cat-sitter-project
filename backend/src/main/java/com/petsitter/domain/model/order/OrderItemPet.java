package com.petsitter.domain.model.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/** 群組 × 毛孩綁定（SD-ORDER §5.1.1 第 4 段，CQ-60）。 */
@Entity
@Table(name = "order_item_pets")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemPet {

    @EmbeddedId
    private Key id;

    public static OrderItemPet of(UUID orderItemId, UUID petId) {
        return new OrderItemPet(new Key(orderItemId, petId));
    }

    public UUID getOrderItemId() {
        return id.getOrderItemId();
    }

    public UUID getPetId() {
        return id.getPetId();
    }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        @Column(name = "order_item_id", nullable = false)
        private UUID orderItemId;
        @Column(name = "pet_id", nullable = false)
        private UUID petId;
    }
}
