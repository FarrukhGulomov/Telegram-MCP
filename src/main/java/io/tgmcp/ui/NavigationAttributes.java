package io.tgmcp.ui;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Thymeleaf 3.1 no longer exposes {@code #request} to template expressions, so the nav bar's
 * active-link highlighting needs the current path handed in explicitly instead.
 */
@ControllerAdvice(assignableTypes = {DashboardController.class, ChatController.class,
        ConnectionController.class, AuditController.class, AccountController.class,
        PlatformAdminController.class})
public class NavigationAttributes {

    private final CurrentActor currentActor;

    public NavigationAttributes(CurrentActor currentActor) {
        this.currentActor = currentActor;
    }

    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }

    /**
     * Governs only the nav link's visibility; {@link PlatformAdminController} enforces the
     * real, config-backed check independently on every request regardless of what the nav
     * happens to show.
     */
    @ModelAttribute("platformAdminVisible")
    public boolean platformAdminVisible() {
        return this.currentActor.isPlatformAdmin();
    }
}
