package io.tgmcp.config;

import java.util.Map;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/**
 * Audit follow-up (monitoring/observability): {@code /actuator/health} only ever answered "is
 * the JVM up", with no way for an operator to see which adapter a running deployment is
 * actually configured against, or whether real-account access is authorized, without reading
 * this exact instance's boot logs. Neither value is sensitive -- both are already logged at
 * INFO on every startup (see {@link TelegramConfiguration}) -- so surfacing them at {@code
 * /actuator/info} costs nothing and answers "which environment is this?" from the outside,
 * without needing log access.
 */
@Component
public class DeploymentInfoContributor implements InfoContributor {

    private final AppProperties properties;

    public DeploymentInfoContributor(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("telegram", Map.of(
                "adapter", this.properties.telegram().adapter().name(),
                "realAccountAccessAuthorized", this.properties.telegram().realAccountAccessAuthorized()));
    }
}
