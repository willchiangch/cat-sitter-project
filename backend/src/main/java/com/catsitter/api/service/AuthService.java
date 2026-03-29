package com.catsitter.api.service;

import com.catsitter.api.dto.auth.AuthTokenResponse;
import com.catsitter.api.dto.auth.LoginRequest;
import com.catsitter.api.dto.auth.RegisterRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.enums.AccountStatus;
import com.catsitter.api.entity.enums.OAuthProvider;
import com.catsitter.api.repository.AccountRepository;
import com.catsitter.api.repository.ProfileRepository;
import com.catsitter.api.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  private final AccountRepository accountRepository;
  private final ProfileRepository profileRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final AuthenticationManager authenticationManager;

  @Value("${application.jwt.expiration}")
  private long jwtExpiration;

  public AuthService(
          AccountRepository accountRepository,
          ProfileRepository profileRepository,
          PasswordEncoder passwordEncoder,
          JwtService jwtService,
          AuthenticationManager authenticationManager
  ) {
    this.accountRepository = accountRepository;
    this.profileRepository = profileRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.authenticationManager = authenticationManager;
  }

  @Transactional
  public AuthTokenResponse register(RegisterRequest request) {
    if (accountRepository.findByEmail(request.email()).isPresent()) {
      throw new RuntimeException("Email already exists");
    }

    Account account = new Account();
    account.setEmail(request.email());
    account.setPasswordHash(passwordEncoder.encode(request.password()));
    account.setOauthProvider(OAuthProvider.LOCAL);
    account.setStatus(AccountStatus.ACTIVE);
    account = accountRepository.save(account);

    Profile profile = new Profile();
    profile.setAccount(account);
    profile.setName(request.displayName());
    profile.setRoleType(request.roleType());
    profileRepository.save(profile);

    account.setLastActiveRole(request.roleType());
    accountRepository.save(account);

    String jwtToken = jwtService.generateToken(account);
    String refreshToken = jwtService.generateRefreshToken(account);

    return new AuthTokenResponse(jwtToken, refreshToken, jwtExpiration / 1000);
  }

  public AuthTokenResponse login(LoginRequest request) {
    authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(
                    request.email(),
                    request.password()
            )
    );
    Account account = accountRepository.findByEmail(request.email())
            .orElseThrow(() -> new RuntimeException("User not found"));

    String jwtToken = jwtService.generateToken(account);
    String refreshToken = jwtService.generateRefreshToken(account);

    return new AuthTokenResponse(jwtToken, refreshToken, jwtExpiration / 1000);
  }

  public com.catsitter.api.dto.auth.AuthMeResponse getMe(Account account) {
    java.util.List<Profile> profiles = profileRepository.findByAccount(account);
    java.util.List<com.catsitter.api.dto.auth.AuthMeResponse.ProfileSummary> summaries = profiles.stream()
            .map(p -> new com.catsitter.api.dto.auth.AuthMeResponse.ProfileSummary(
                    p.getId(),
                    p.getRoleType(),
                    p.getName(),
                    null // avatarUrl placeholder
            ))
            .toList();

    return new com.catsitter.api.dto.auth.AuthMeResponse(
            account.getId(),
            account.getEmail(),
            account.getLastActiveRole(),
            account.isEmailVerified(),
            summaries
    );
  }

  @Transactional
  public com.catsitter.api.dto.auth.AuthMeResponse switchRole(Account account, com.catsitter.api.entity.enums.RoleType roleType) {
    account.setLastActiveRole(roleType);
    accountRepository.save(account);
    return getMe(account);
  }
}
