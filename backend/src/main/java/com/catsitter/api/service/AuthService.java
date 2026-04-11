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
  private final EmailVerificationService emailVerificationService;
  private final com.catsitter.api.service.storage.StorageService storageService;

  @Value("${application.jwt.expiration}")
  private long jwtExpiration;

  public AuthService(
          AccountRepository accountRepository,
          ProfileRepository profileRepository,
          PasswordEncoder passwordEncoder,
          JwtService jwtService,
          AuthenticationManager authenticationManager,
          EmailVerificationService emailVerificationService,
          com.catsitter.api.service.storage.StorageService storageService
  ) {
    this.accountRepository = accountRepository;
    this.profileRepository = profileRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.authenticationManager = authenticationManager;
    this.emailVerificationService = emailVerificationService;
    this.storageService = storageService;
  }

  @Transactional
  public com.catsitter.api.dto.auth.AuthMeResponse updateEmail(Account account, String newEmail) {
    if (accountRepository.findByEmail(newEmail).isPresent() && !account.getEmail().equals(newEmail)) {
      throw new RuntimeException("Email is already taken by another account");
    }

    String oldEmail = account.getEmail();
    System.out.println("\n\n>>> AUTH SERVICE: updateEmail START for " + account.getId());
    System.out.println(">>> FROM: " + oldEmail + " TO: " + newEmail + "\n\n");

    account.setEmail(newEmail);
    account.setEmailVerified(false);
    accountRepository.saveAndFlush(account);

    System.out.println(">>> AUTH SERVICE: Account saved to DB with NEW email: " + account.getEmail());
    System.out.println(">>> TRIGGERING emailVerificationService.sendVerificationCode...\n");
    
    emailVerificationService.sendVerificationCode(account);

    System.out.println(">>> AUTH SERVICE: updateEmail FINISHED for " + account.getId() + "\n\n");
    return getMe(account);
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

    if (request.roleType() != null) {
      Profile profile = new Profile();
      profile.setAccount(account);
      profile.setName(request.displayName());
      profile.setRoleType(request.roleType());
      profileRepository.save(profile);

      account.setLastActiveRole(request.roleType());
      accountRepository.save(account);
    }

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
                    storageService.getUrl(p.getAvatarUrl())
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

  @Transactional
  public com.catsitter.api.dto.auth.AuthMeResponse completeOnboarding(Account account, com.catsitter.api.dto.auth.CompleteOnboardingRequest request) {
    if (profileRepository.findByAccountAndRoleType(account, request.roleType()).isPresent()) {
      // If already exists, just update role and return
      return switchRole(account, request.roleType());
    }

    // Check name uniqueness (simple check, entity also has constraint)
    if (profileRepository.findByName(request.displayName()).isPresent()) {
      throw new RuntimeException("Display name is already taken");
    }

    Profile profile = new Profile();
    profile.setAccount(account);
    profile.setName(request.displayName());
    profile.setRoleType(request.roleType());
    profileRepository.save(profile);

    account.setLastActiveRole(request.roleType());
    accountRepository.save(account);

    return getMe(account);
  }
}
