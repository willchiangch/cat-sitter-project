package com.petsitter.application.port;

import java.util.UUID;

/** 保母公開狀態（SD-ORDER §2.4.2；SD-SUPPLY 實作，過渡）。 */
public interface PublicProfileQuery {

    /** 保母是否存在（未刪除且有 SITTER profile）：E1 / E31 的 404 判斷（過渡補充方法）。 */
    boolean sitterExists(UUID sitterId);

    boolean isVisible(UUID sitterId);
}
