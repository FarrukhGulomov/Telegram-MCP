package io.tgmcp.config;

import java.time.Duration;
import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * The management UI is available in English, Uzbek and Russian. A visitor's choice is
 * remembered in a cookie (not the session) so it survives across sign-in/sign-out and applies
 * on the public /login page too. Defaulting to English rather than sniffing Accept-Language
 * keeps behaviour predictable for both operators and tests; a switcher link is all it takes to
 * change it, on every page.
 */
@Configuration(proxyBeanMethods = false)
public class LocaleConfig implements WebMvcConfigurer {

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("TGMCP_LANG");
        resolver.setCookieMaxAge(Duration.ofDays(365));
        resolver.setDefaultLocaleFunction(request -> Locale.ENGLISH);
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        interceptor.setIgnoreInvalidLocale(true);
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor())
                .excludePathPatterns("/mcp/**", "/oauth2/**", "/.well-known/**");
    }
}
