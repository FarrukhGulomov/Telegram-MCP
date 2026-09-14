// Switches the UI language by re-requesting the current page with ?lang=xx. The server's
// LocaleChangeInterceptor reads that parameter, stores the choice in a long-lived cookie, and
// serves the page back already translated — this script only needs to build the URL.
document.querySelectorAll('.lang-switch a[data-lang]').forEach((link) => {
    link.addEventListener('click', (event) => {
        event.preventDefault();
        const url = new URL(window.location.href);
        url.searchParams.set('lang', link.dataset.lang);
        window.location.href = url.toString();
    });
});
