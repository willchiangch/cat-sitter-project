package com.petsitter.application.service;

import org.springframework.stereotype.Service;

@Service
public class SystemConfigService {
    
    public int getTemplateLimit() {
        return 10; // Default Mock limit for templates
    }
    
    public int getMediaLimit() {
        return 20; // Default Mock limit for media
    }
}
