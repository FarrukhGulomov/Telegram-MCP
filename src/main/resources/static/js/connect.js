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
