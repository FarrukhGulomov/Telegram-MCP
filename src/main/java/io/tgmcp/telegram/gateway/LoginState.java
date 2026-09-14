package io.tgmcp.telegram.gateway;

/**
 * Login progress as shown in the management UI.
 *
 * <p>{@code qrLoginLink} is the only secret-adjacent value here; it is rendered exclusively
 * on the protected onboarding page, never logged and never reachable from an MCP tool.
 */
public record LoginState(LoginStage stage,
                         String qrLoginLink,
                         Long tgUserId,
                         String tgUsername,
                         String message) {

    public static LoginState of(LoginStage stage, String message) {
        return new LoginState(stage, null, null, null, message);
    }

    public boolean ready() {
        return stage == LoginStage.READY;
    }
}
