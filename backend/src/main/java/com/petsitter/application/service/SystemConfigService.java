package com.petsitter.application.service;

import org.springframework.stereotype.Service;

@Service
public class SystemConfigService {
    
    public int getTemplateLimit() {
        return 3; // PRD-021: 每位保母最多持有 3 個模板
    }
    
    public int getMediaLimit() {
        return 20; // Default Mock limit for media
    }
}
