package com.catsitter.api.dto;

import com.catsitter.api.entity.Profile;
import java.util.UUID;

public class ProfileMiniDTO {
    private UUID id;
    private String name;
    private String avatarUrl;
    private String slug;
    private String address;

    public ProfileMiniDTO() {}

    public static ProfileMiniDTO fromEntity(Profile profile) {
        if (profile == null) return null;
        ProfileMiniDTO dto = new ProfileMiniDTO();
        dto.setId(profile.getId());
        dto.setName(profile.getName());
        dto.setAvatarUrl(profile.getAvatarUrl());
        dto.setSlug(profile.getSlug());
        dto.setAddress(profile.getAddress());
        return dto;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
}
