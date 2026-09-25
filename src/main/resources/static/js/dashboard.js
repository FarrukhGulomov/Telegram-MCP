// Client-side filter over the already-rendered chat table: the full list (contacts, groups,
// channels) can be long, and there was previously no way to jump to one chat by name other than
// scrolling. Filtering hides non-matching rows rather than removing them, so a checkbox checked
// before or during a search stays checked (and still submits) even while its row is hidden.
//
// Live report: a contact whose Telegram title was visibly findable by scrolling the full list
// (e.g. "Отабек Шина") could still not be found by typing that exact same text into this search
// box. Some contact names contain a non-breaking space (U+00A0) or other Unicode whitespace
// variant between words -- invisible and indistinguishable from a normal space when read, but a
// different character to a byte-for-byte String#indexOf, so a query typed with an ordinary space
// never matched. `\s` in a JS regex already covers NBSP and the other Unicode space separators,
// so collapsing all whitespace runs to a single normal space on both sides before comparing makes
// the match resilient to whichever side (if either) happens to carry the unusual character.
document.querySelectorAll('[data-chat-search]').forEach((input) => {
    const table = input.closest('form')?.querySelector('table');
    if (!table) {
        return;
    }
    const rows = Array.from(table.querySelectorAll('[data-chat-row]'));
    const noResultsRow = document.getElementById('chat-search-no-results');
    const normalize = (text) => (text || '').toLowerCase().replace(/\s+/g, ' ').trim();

    const applyFilter = () => {
        const query = normalize(input.value);
        let visibleCount = 0;
        rows.forEach((row) => {
            const matches = query === '' || normalize(row.dataset.chatSearchText).indexOf(query) !== -1;
            row.hidden = !matches;
            if (matches) {
                visibleCount += 1;
            }
        });
        if (noResultsRow) {
            noResultsRow.hidden = visibleCount > 0;
        }
    };

    input.addEventListener('input', applyFilter);
    applyFilter();
});
