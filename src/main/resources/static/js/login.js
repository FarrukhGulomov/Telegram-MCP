// A QR scan confirms out of band (another device), so polling is the only way this page finds
// out -- once it does, /login/finish reads the now-READY connection back and signs in, exactly
// the way /login/code and /login/password already do synchronously for their own methods.
(function () {
    // READY is included here too: a page load can land on this stage directly (not just reach
    // it mid-poll) when a pending login already completed on Telegram's side but this browser's
    // authenticated session was lost before /login/finish ever ran -- e.g. a server restart
    // between reaching READY and the hidden form submitting. Without READY in this list the
    // page below renders nothing for that stage and this script, seeing it isn't a stage it
    // polls, does nothing either: a login that will never finish itself.
    const POLLING_STAGES = ['WAIT_QR_SCAN', 'WAIT_CODE', 'WAIT_PASSWORD', 'READY'];
    const shell = document.querySelector('[data-login-stage]');
    if (!shell) {
        return;
    }
    const initialStage = shell.dataset.loginStage;
    if (POLLING_STAGES.indexOf(initialStage) === -1) {
        return;
    }
    const pollIntervalMs = 2000;
    const maxAttempts = 150; // ~5 minutes, so an abandoned tab doesn't poll forever
    let attempts = 0;

    function poll() {
        attempts += 1;
        fetch('/login/status', {headers: {Accept: 'application/json'}})
            .then((response) => response.json())
            .then((data) => {
                if (data.stage === 'READY') {
                    // Security-audit finding SEC-R07: /login/finish is a POST requiring both a
                    // CSRF token (from the hidden form itself, server-rendered like every other
                    // form on this page) and the single-use nonce /login/status just handed
                    // back now that the stage really is READY.
                    const form = document.getElementById('finishLoginForm');
                    const nonceField = form && form.elements.nonce;
                    if (form && nonceField && data.nonce) {
                        nonceField.value = data.nonce;
                        form.submit();
                    }
                    return;
                }
                if (data.stage && data.stage !== initialStage) {
                    window.location.reload();
                    return;
                }
                if (attempts < maxAttempts) {
                    setTimeout(poll, pollIntervalMs);
                }
            })
            .catch(() => {
                if (attempts < maxAttempts) {
                    setTimeout(poll, pollIntervalMs);
                }
            });
    }

    setTimeout(poll, pollIntervalMs);
})();
