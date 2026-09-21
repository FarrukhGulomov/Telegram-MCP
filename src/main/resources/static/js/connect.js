// Shows the phone-number field only when "Phone number" is the selected login method, instead
// of always showing it with a "(only for the phone method)" caveat. Purely a display toggle:
// the server still accepts (and needs) phoneNumber only for the PHONE_NUMBER method regardless
// of what this script does, so nothing here is a security or validation boundary.
document.querySelectorAll('[data-method-select]').forEach((select) => {
    const group = select.closest('form')?.querySelector('[data-phone-group]');
    if (!group) {
        return;
    }
    const sync = () => {
        group.hidden = select.value !== 'PHONE_NUMBER';
    };
    select.addEventListener('change', sync);
    sync();
});

// Confirmed from live production logs: submitting a correct code or password sends the request
// to TDLib and returns immediately with whatever login stage was still true at that instant --
// for a correct password that is still WAIT_PASSWORD, since TDLib reports the real
// authorizationStateReady transition moments later, asynchronously (ConnectionController#status).
// Without this poll, the connect page kept showing the very form the operator had just correctly
// completed, with nothing telling them it had actually worked, until they reloaded it themselves.
(function () {
    const POLLING_STAGES = ['WAIT_QR_SCAN', 'WAIT_CODE', 'WAIT_PASSWORD'];
    const section = document.querySelector('[data-connect-stage]');
    if (!section) {
        return;
    }
    const initialStage = section.dataset.connectStage;
    if (POLLING_STAGES.indexOf(initialStage) === -1) {
        return;
    }
    const pollIntervalMs = 2000;
    const maxAttempts = 150; // ~5 minutes, so an abandoned tab doesn't poll forever
    let attempts = 0;

    function poll() {
        attempts += 1;
        fetch('/connect/status', {headers: {Accept: 'application/json'}})
            .then((response) => response.json())
            .then((data) => {
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
