package com.catsitter.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {

  @Value("${application.jwt.secret-key}")
  private String secretKey;
  @Value("${application.jwt.expiration}")
  private long jwtExpiration;
  @Value("${application.jwt.refresh-token.expiration}")
  private long refreshExpiration;

  public String extractUsername(String token) {
    return extractClaim(token, Claims::getSubject);
  }

  public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
    final Claims claims = extractAllClaims(token);
    return claimsResolver.apply(claims);
  }

  public String generateToken(UserDetails userDetails) {
    return generateToken(new HashMap<>(), userDetails);
  }

  public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
    return buildToken(extraClaims, userDetails, jwtExpiration);
  }

  public String generateRefreshToken(UserDetails userDetails) {
    return buildToken(new HashMap<>(), userDetails, refreshExpiration);
  }

  private String buildToken(
          Map<String, Object> extraClaims,
          UserDetails userDetails,
          long expiration
  ) {
    String subject = (userDetails instanceof com.catsitter.api.entity.Account) 
            ? ((com.catsitter.api.entity.Account) userDetails).getId().toString() 
            : userDetails.getUsername();

    return Jwts
            .builder()
            .claims(extraClaims)
            .subject(subject)
            .issuedAt(new Date(System.currentTimeMillis()))
            .expiration(new Date(System.currentTimeMillis() + expiration))
            .signWith(getSignInKey())
            .compact();
  }

  public boolean isTokenValid(String token, UserDetails userDetails) {
    try {
      final String subject = extractUsername(token);
      String userIdentifier = (userDetails instanceof com.catsitter.api.entity.Account)
              ? ((com.catsitter.api.entity.Account) userDetails).getId().toString()
              : userDetails.getUsername();
              
      boolean isExpired = isTokenExpired(token);
      boolean matches = subject.equals(userIdentifier);
      
      if (!matches || isExpired) {
        System.out.println("[JWT-DEBUG] Token validation failed!");
        System.out.println("  - Subject: " + subject);
        System.out.println("  - UserID : " + userIdentifier);
        System.out.println("  - Expired: " + isExpired);
      }
      
      return matches && !isExpired;
    } catch (Exception e) {
      System.err.println("[JWT-DEBUG] Token validation error: " + e.getMessage());
      return false;
    }
  }

  private boolean isTokenExpired(String token) {
    try {
      return extractExpiration(token).before(new Date());
    } catch (Exception e) {
      System.err.println("[JWT-DEBUG] Expiration check failed: " + e.getMessage());
      return true;
    }
  }

  private Date extractExpiration(String token) {
    return extractClaim(token, Claims::getExpiration);
  }

  private Claims extractAllClaims(String token) {
    return Jwts
            .parser()
            .verifyWith(getSignInKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
  }

  private SecretKey getSignInKey() {
    byte[] keyBytes = Decoders.BASE64.decode(secretKey);
    return Keys.hmacShaKeyFor(keyBytes);
  }
}
