package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import com.catsitter.api.entity.enums.TransferStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Entity
@Table(name = "order_transfers")
public class OrderTransfer extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false)
  private Order order;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "from_sitter_id", nullable = false)
  private Profile fromSitter;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "to_sitter_id", nullable = false)
  private Profile toSitter;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "transfer_status", nullable = false, length = 50)
  private TransferStatus transferStatus;

  public UUID getId() { return id; }
  public Order getOrder() { return order; }
  public void setOrder(Order order) { this.order = order; }
  public Profile getFromSitter() { return fromSitter; }
  public void setFromSitter(Profile fromSitter) { this.fromSitter = fromSitter; }
  public Profile getToSitter() { return toSitter; }
  public void setToSitter(Profile toSitter) { this.toSitter = toSitter; }
  public TransferStatus getTransferStatus() { return transferStatus; }
  public void setTransferStatus(TransferStatus transferStatus) { this.transferStatus = transferStatus; }
}
