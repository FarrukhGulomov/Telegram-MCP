package io.tgmcp.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Keeps stack traces and internal identifiers out of the browser. */
@ControllerAdvice(assignableTypes = {DashboardController.class, ChatController.class,
        ConnectionController.class, AuditController.class})
public class UiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public String handleBadRequest(IllegalArgumentException ex, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("error", ex.getMessage());
        return "redirect:/";
    }

    @ExceptionHandler(IllegalStateException.class)
    public String handleConflict(IllegalStateException ex, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("error", ex.getMessage());
        return "redirect:/";
    }

    @ExceptionHandler(RuntimeException.class)
    public String handleUnexpected(RuntimeException ex, RedirectAttributes redirectAttributes) {
        log.error("Unhandled UI error", ex);
        redirectAttributes.addFlashAttribute("error", "Something went wrong. Check the server log.");
        return "redirect:/";
    }
}
