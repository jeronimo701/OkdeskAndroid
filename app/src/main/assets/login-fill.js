(function (credentials, origin) {
    'use strict';
    if (location.origin !== origin) return 'origin';
    const visible = (input) => !input.disabled && !input.readOnly && input.getClientRects().length > 0;
    const passwords = [...document.querySelectorAll('input[type="password"]')].filter(visible);
    if (passwords.length !== 1) return 'ambiguous';
    const password = passwords[0];
    const form = password.form || password.closest('form') || document;
    const candidates = [...form.querySelectorAll('input')].filter((input) =>
        visible(input) && ['text', 'email', 'tel'].includes(input.type));
    const named = candidates.filter((input) => /login|username|email|e_mail/i.test(`${input.name} ${input.id} ${input.autocomplete}`));
    const usernames = named.length ? named : candidates;
    if (usernames.length !== 1) return 'ambiguous';
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    for (const [input, value] of [[usernames[0], credentials.username], [password, credentials.password]]) {
        setter.call(input, value);
        input.dispatchEvent(new Event('input', { bubbles: true }));
        input.dispatchEvent(new Event('change', { bubbles: true }));
    }
    return usernames[0].value === credentials.username && password.value === credentials.password ? 'ok' : 'changed';
})
