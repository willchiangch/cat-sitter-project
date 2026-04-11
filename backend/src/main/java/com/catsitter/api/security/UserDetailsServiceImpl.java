package com.catsitter.api.security;

import com.catsitter.api.repository.AccountRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

  private final AccountRepository accountRepository;

  public UserDetailsServiceImpl(AccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    // Check if the username is a UUID
    if (isValidUUID(username)) {
      return accountRepository.findById(java.util.UUID.fromString(username))
              .orElseThrow(() -> new UsernameNotFoundException("User not found by ID: " + username));
    }
    
    // Default to email lookup (for login)
    return accountRepository.findByEmail(username)
            .orElseThrow(() -> new UsernameNotFoundException("User not found by email: " + username));
  }

  private boolean isValidUUID(String str) {
    if (str == null || str.length() != 36) return false;
    try {
      java.util.UUID.fromString(str);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
