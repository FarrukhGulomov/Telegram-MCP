package io.tgmcp.telegram.gateway;

/**
 * Mirrors the TDLib authorization states this MVP supports
 * ({@code authorizationStateWaitPhoneNumber}, {@code ...WaitCode}, {@code ...WaitPassword},
 * {@code ...WaitOtherDeviceConfirmation} for QR login, {@code ...Ready}, {@code ...Closed}).
 */
public enum LoginStage {
    DISCONNECTED,
    WAIT_PHONE_NUMBER,
    WAIT_CODE,
    /** Two-factor authentication password required. */
    WAIT_PASSWORD,
    /** QR login: the user must scan the link from an already-signed-in Telegram app. */
    WAIT_QR_SCAN,
    READY,
    ERROR
}
