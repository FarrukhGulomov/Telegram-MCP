package io.tgmcp.telegram.gateway;

/**
 * Login progress as shown in the management UI.
 *
 * <p>{@code qrLoginLink} is the only secret-adjacent value here; it is rendered exclusively
 * on the protected onboarding page, never logged and never reachable from an MCP tool.
 *
 * <p>{@code tgPhoneNumber} is populated only once {@link #stage} reaches {@link
 * LoginStage#READY}, from Telegram's own account record ({@code getMe}) -- never from
 * whatever a caller typed into a phone-number field. Since sign-in is now the same act as
 * connecting Telegram (see {@code PreAuthLoginService}), this is what proves which real
 * account a login belongs to, independent of any phone number a form submitted.
 */
public record LoginState(LoginStage stage,
                         String qrLoginLink,
                         Long tgUserId,
                         String tgUsername,
                         String tgPhoneNumber,
                         String message) {

    public static LoginState of(LoginStage stage, String message) {
        return new LoginState(stage, null, null, null, null, message);
    }

    public boolean ready() {
        return stage == LoginStage.READY;
    }
}
