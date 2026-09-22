package io.tgmcp.ui;

import java.util.List;

import io.tgmcp.platformadmin.PlatformAdminService;
import io.tgmcp.platformadmin.TenantSummary;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Cross-tenant operational view for whoever operates this deployment: how many tenants and
 * users exist, how much of the platform is actually connected or active, and a merged audit
 * feed (including a filtered "suspicious activity" view) spanning every tenant -- never a
 * single tenant's own view, which stays at {@link AuditController}. Reachable only by the
 * phone numbers configured under {@code tgmcp.platform-admin.phone-numbers} (see {@link
 * CurrentActor#requirePlatformAdmin()}); nothing about that allowlist is editable from inside
 * this application, so no tenant-scoped OWNER/ADMIN can ever grant themselves access to it.
 */
@Controller
public class PlatformAdminController {

    private final CurrentActor currentActor;
    private final PlatformAdminService platformAdminService;

    public PlatformAdminController(CurrentActor currentActor, PlatformAdminService platformAdminService) {
        this.currentActor = currentActor;
        this.platformAdminService = platformAdminService;
    }

    @GetMapping("/platform-admin")
    public String overview(Model model) {
        this.currentActor.requirePlatformAdmin();

        List<TenantSummary> tenants = this.platformAdminService.tenantSummaries();
        model.addAttribute("tenants", tenants);
        model.addAttribute("totalTenants", tenants.size());
        model.addAttribute("totalUsers", tenants.stream().mapToLong(TenantSummary::userCount).sum());
        model.addAttribute("totalChats", tenants.stream().mapToLong(TenantSummary::chatCount).sum());
        model.addAttribute("totalActiveChats", tenants.stream().mapToLong(TenantSummary::activeChatCount).sum());
        model.addAttribute("totalConnectedAccounts",
                tenants.stream().mapToLong(TenantSummary::connectedTelegramAccounts).sum());
        model.addAttribute("recentEvents", this.platformAdminService.recentEvents(100));
        model.addAttribute("suspiciousEvents", this.platformAdminService.recentSuspiciousEvents(50));
        return "platform-admin";
    }
}
