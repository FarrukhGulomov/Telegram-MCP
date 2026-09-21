// A QR scan confirms out of band (another device), so polling is the only way this page finds
// out -- once it does, /login/finish reads the now-READY connection back and signs in, exactly
// the way /login/code and /login/password already do synchronously for their own methods.
(function () {
    const POLLING_STAGES = ['WAIT_QR_SCAN', 'WAIT_CODE', 'WAIT_PASSWORD'];
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
                    window.location.href = '/login/finish';
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
