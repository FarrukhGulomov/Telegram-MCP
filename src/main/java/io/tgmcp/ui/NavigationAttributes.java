package io.tgmcp.ui;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Thymeleaf 3.1 no longer exposes {@code #request} to template expressions, so the nav bar's
 * active-link highlighting needs the current path handed in explicitly instead.
 */
@ControllerAdvice(assignableTypes = {DashboardController.class, ChatController.class,
        ConnectionController.class, AuditController.class})
public class NavigationAttributes {

    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
