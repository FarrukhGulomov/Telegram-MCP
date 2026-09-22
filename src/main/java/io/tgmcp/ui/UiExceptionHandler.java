package io.tgmcp.ui;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.MessageSource;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Keeps stack traces and internal identifiers out of the browser. */
@ControllerAdvice(assignableTypes = {DashboardController.class, ChatController.class,
        ConnectionController.class, AuditController.class, PlatformAdminController.class})
public class UiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UiExceptionHandler.class);

    private final MessageSource messages;

    public UiExceptionHandler(MessageSource messages) {
        this.messages = messages;
    }

    /**
     * {@code ex.getMessage()} is usually a short message code (e.g. {@code
     * "validation.selectProvider"}) raised by a service in this pass; {@link Messages#text}
     * resolves it in the visitor's language, falling back to the raw message untranslated for
     * any exception this pass didn't migrate.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public String handleBadRequest(IllegalArgumentException ex, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
        return "redirect:/";
    }

    /**
     * A required form field arrived empty (a checkbox group left fully unchecked submits no
     * field at all) or a value couldn't be parsed (letters typed into a number field). Both
     * would otherwise surface as a raw, unstyled 400 error page. Sending the visitor back to the
     * page they were just on with a plain-language explanation is friendlier and safer than
     * showing them a stack trace.
     */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class})
    public String handleMalformedForm(Exception ex, HttpServletRequest request,
                                      RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, "error.malformedForm"));
        return "redirect:" + backTo(request);
    }

    /** Same-origin Referer only: never bounce a visitor's own browser off to another site. */
    private static String backTo(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer != null) {
            try {
                URI uri = URI.create(referer);
                if (uri.getHost() != null && uri.getHost().equalsIgnoreCase(request.getServerName())
                        && uri.getPath() != null && uri.getPath().startsWith("/")) {
                    return uri.getPath();
                }
            }
            catch (IllegalArgumentException ignored) {
                // Malformed Referer header: fall through to the safe default below.
            }
        }
        return "/";
    }

    @ExceptionHandler(IllegalStateException.class)
    public String handleConflict(IllegalStateException ex, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
        return "redirect:/";
    }

    @ExceptionHandler(RuntimeException.class)
    public String handleUnexpected(RuntimeException ex, RedirectAttributes redirectAttributes) {
        log.error("Unhandled UI error", ex);
        redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, "error.unexpected"));
        return "redirect:/";
    }
}
