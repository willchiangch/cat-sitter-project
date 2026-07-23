package com.petsitter.infrastructure.security;

import com.petsitter.domain.model.User;
import com.petsitter.domain.model.WebAuthnCredential;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.domain.repository.WebAuthnCredentialRepository;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.exception.Base64UrlException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Yubico webauthn-server-core 要求的資料存取抽象層。
 * username 對應 {@link User#getEmail()}；user handle 用 {@link WebAuthnUserHandleUtil} 直接以 UUID 表示，
 * 不另外儲存對照表。
 *
 * <p>刻意不取名 {@code WebAuthnCredentialRepositoryImpl}——Spring Data JPA 會把符合
 * {@code <RepositoryInterface>Impl} 命名慣例的 Bean 自動當成該 Repository 介面的自訂實作片段注入，
 * 這裡剛好命名撞上 {@link WebAuthnCredentialRepository}，會造成 Bean 建立時的循環參照。
 */
@Component
@RequiredArgsConstructor
public class WebAuthnRpCredentialLookup implements CredentialRepository {

    private final UserRepository userRepository;
    private final WebAuthnCredentialRepository webAuthnCredentialRepository;

    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        Optional<User> user = userRepository.findByEmail(username);
        if (user.isEmpty()) {
            return new HashSet<>();
        }
        Set<PublicKeyCredentialDescriptor> descriptors = new HashSet<>();
        for (WebAuthnCredential credential : webAuthnCredentialRepository.findByUserId(user.get().getId())) {
            descriptors.add(PublicKeyCredentialDescriptor.builder()
                    .id(decodeBase64Url(credential.getCredentialId()))
                    .build());
        }
        return descriptors;
    }

    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        return userRepository.findByEmail(username)
                .map(user -> WebAuthnUserHandleUtil.toUserHandle(user.getId()));
    }

    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        return userRepository.findById(WebAuthnUserHandleUtil.toUserId(userHandle))
                .map(User::getEmail);
    }

    @Override
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        return webAuthnCredentialRepository.findByCredentialId(credentialId.getBase64Url())
                .filter(credential -> credential.getUserId().equals(WebAuthnUserHandleUtil.toUserId(userHandle)))
                .map(this::toRegisteredCredential);
    }

    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        return webAuthnCredentialRepository.findByCredentialId(credentialId.getBase64Url())
                .map(credential -> Set.of(toRegisteredCredential(credential)))
                .orElseGet(Set::of);
    }

    private RegisteredCredential toRegisteredCredential(WebAuthnCredential credential) {
        return RegisteredCredential.builder()
                .credentialId(decodeBase64Url(credential.getCredentialId()))
                .userHandle(WebAuthnUserHandleUtil.toUserHandle(credential.getUserId()))
                .publicKeyCose(decodeBase64Url(credential.getPublicKeyCose()))
                .signatureCount(credential.getSignCount())
                .build();
    }

    /**
     * 這裡解碼的都是我方自己存入 DB 的 base64url 字串，理論上不會解碼失敗；
     * 失敗代表資料已損毀，屬 should-never-happen 內部錯誤，直接轉為未檢查例外。
     */
    private static ByteArray decodeBase64Url(String base64Url) {
        try {
            return ByteArray.fromBase64Url(base64Url);
        } catch (Base64UrlException e) {
            throw new IllegalStateException("WebAuthn 憑證資料解碼失敗: " + base64Url, e);
        }
    }
}
