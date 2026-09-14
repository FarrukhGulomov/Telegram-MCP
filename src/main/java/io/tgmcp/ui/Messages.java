package io.tgmcp.ui;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * Resolves a message code to text in the current request's language. Falls back to the code
 * itself when no translation is registered, so a stray, un-migrated English literal (thrown
 * from code this pass didn't touch) still displays instead of breaking.
 */
public final class Messages {

    private Messages() {
    }

    public static String text(MessageSource source, String code, Object... args) {
        return source.getMessage(code, args, code, LocaleContextHolder.getLocale());
    }
}
