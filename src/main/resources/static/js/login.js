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
    //
    // WAIT_PHONE_NUMBER is included for the same reason found in real production use: beginLogin
    // sends setAuthenticationPhoneNumber and returns, but the resulting authorizationStateWaitCode
    // transition is pushed asynchronously (a real network round trip to Telegram) -- a page load
    // that lands before that push arrives sees the client still reporting the stage it was in
    // *before* the phone number was submitted. Without polling here, that page never advances on
    // its own: the phone-entry form was already replaced by the redirect to this same stage, and
    // nothing this page renders for WAIT_PHONE_NUMBER submits it again.
    const POLLING_STAGES = ['WAIT_PHONE_NUMBER', 'WAIT_QR_SCAN', 'WAIT_CODE', 'WAIT_PASSWORD', 'READY'];
    const shell = document.querySelector('[data-login-stage]');
    if (!shell) {
        return;
    }
    const initialStage = shell.dataset.loginStage;
    if (POLLING_STAGES.indexOf(initialStage) === -1) {
        return;
    }
    // Live report: the WAIT_CODE/WAIT_PASSWORD -> READY transition can take 30-40+ seconds in
    // practice (Telegram completing sign-in server-side, confirmed from production logs) --
    // shortened from 2000ms so the page catches up to it sooner once it lands, without polling
    // meaningfully more often in absolute terms (attempts scaled to keep the same ~5 minute
    // total budget, so an abandoned tab still doesn't poll forever).
    const pollIntervalMs = 1200;
    const maxAttempts = 250; // ~5 minutes at pollIntervalMs
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
