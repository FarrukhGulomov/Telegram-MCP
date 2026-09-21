// Client-side filter over the already-rendered chat table: the full list (contacts, groups,
// channels) can be long, and there was previously no way to jump to one chat by name other than
// scrolling. Filtering hides non-matching rows rather than removing them, so a checkbox checked
// before or during a search stays checked (and still submits) even while its row is hidden.
document.querySelectorAll('[data-chat-search]').forEach((input) => {
    const table = input.closest('form')?.querySelector('table');
    if (!table) {
        return;
    }
    const rows = Array.from(table.querySelectorAll('[data-chat-row]'));
    const noResultsRow = document.getElementById('chat-search-no-results');

    const applyFilter = () => {
        const query = input.value.trim().toLowerCase();
        let visibleCount = 0;
        rows.forEach((row) => {
            const matches = query === '' || row.dataset.chatSearchText.indexOf(query) !== -1;
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
