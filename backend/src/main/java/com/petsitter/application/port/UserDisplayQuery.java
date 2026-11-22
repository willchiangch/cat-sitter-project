package com.petsitter.application.port;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 當下顯示名稱（SD-ORDER §2.4.1 getOrderContexts「讀當下顯示名稱，不快照」；SD-IDENTITY / SD-SUPPLY 實作，過渡）。
 * 保母：公開檔案顯示名稱，未設定則帳號名稱；飼主：帳號名稱。
 */
public interface UserDisplayQuery {

    Map<UUID, String> sitterDisplayNames(Set<UUID> sitterIds);

    Map<UUID, String> ownerDisplayNames(Set<UUID> ownerIds);

    /** 帳號 email 網域為 {@code @e2e-journey.test}（C11 SUPPRESSED_TEST、test-data 範圍）。 */
    boolean isTestAccount(UUID userId);
}
