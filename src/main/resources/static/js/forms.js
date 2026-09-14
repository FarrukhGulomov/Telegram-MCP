// Prevents double-submitting a form (double-click, or clicking again while a slow request is
// still in flight). A second submission of the *same rendered page* carries a CSRF token that
// no longer matches once the first submission has already changed session state (for example,
// a successful login regenerates the session id) — the browser then follows a stale form
// straight into a 403. Disabling the button after the first submit avoids that class of report
// entirely; it has no bearing on the server-side security checks themselves.
document.querySelectorAll('form[data-guard-resubmit]').forEach((form) => {
    form.addEventListener('submit', () => {
        if (form.dataset.submitted === 'true') {
            return;
        }
        form.dataset.submitted = 'true';
        form.querySelectorAll('button[type="submit"]').forEach((button) => {
            button.disabled = true;
            button.dataset.originalText = button.textContent;
            button.textContent = 'Please wait…';
        });
    });
});
