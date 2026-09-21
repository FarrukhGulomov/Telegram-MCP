// Polls for the "open the bot and press Start" step (RegistrationController#linkStatus) to
// complete, then follows the redirect the server decides on -- so the visitor never has to come
// back and click anything themselves once they've sent /start in their Telegram app.
(function () {
    var config = document.getElementById('link-telegram-config');
    if (!config) {
        return;
    }
    var token = config.dataset.linkToken;
    var statusEl = document.getElementById('link-telegram-status');
    var pollIntervalMs = 2000;

    function poll() {
        fetch('/register/link-telegram/status?token=' + encodeURIComponent(token), {
            headers: {Accept: 'application/json'},
        })
            .then(function (response) {
                return response.json();
            })
            .then(function (data) {
                if (data.redirectTo) {
                    window.location.href = data.redirectTo;
                    return;
                }
                setTimeout(poll, pollIntervalMs);
            })
            .catch(function () {
                setTimeout(poll, pollIntervalMs);
            });
    }

    if (statusEl) {
        setTimeout(poll, pollIntervalMs);
    }
})();
