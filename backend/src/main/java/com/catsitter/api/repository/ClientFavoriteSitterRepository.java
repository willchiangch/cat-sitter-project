package com.catsitter.api.repository;

import com.catsitter.api.entity.ClientFavoriteSitter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClientFavoriteSitterRepository extends JpaRepository<ClientFavoriteSitter, UUID> {
  List<ClientFavoriteSitter> findByClientProfileIdAndIsFavoriteTrue(UUID clientProfileId);
}
