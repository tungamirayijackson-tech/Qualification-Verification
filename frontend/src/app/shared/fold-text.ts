/**
 * Folds text for filtering, the way the register folds names.
 *
 * The server's `SearchName` lower-cases, strips accents and drops punctuation before it stores a
 * name and before it searches for one, so "renee" finds "Renée" and "MAHLANGU" finds "Mahlangu".
 * The screens that filter a list in the browser — awarding bodies, qualifications — do not go
 * through the server at all, and a filter that only matched exact case would contradict what the
 * rest of the console promises.
 *
 * This is a deliberate, narrow copy of that rule, and worth being honest about. It is duplicated
 * logic and duplicated logic drifts. What keeps it safe is scope: nothing here is ever stored,
 * signed, or compared against anything the server computed. It decides only which rows of an
 * already-fetched list to show. The register's own search still folds server-side, where it must,
 * because the trigram index it queries was built with that folding.
 */

/** Combining marks left behind by NFD decomposition — the accents themselves. */
const COMBINING_MARKS = /\p{M}+/gu;

/** Anything that is not a letter, a digit or a space, once folded. */
const NON_SEARCHABLE = /[^\p{L}\p{N} ]/gu;

const RUNS_OF_SPACE = /\s+/g;

/**
 * Reduces text to its comparable form: lower case, no accents, no punctuation.
 *
 * @param text the text as written, which may be null
 * @returns the folded text, or an empty string when there was nothing
 */
export function fold(text: string | null | undefined): string {
  if (!text) {
    return '';
  }
  return text
    .normalize('NFD')
    .replace(COMBINING_MARKS, '')
    .toLowerCase()
    .replace(NON_SEARCHABLE, ' ')
    .replace(RUNS_OF_SPACE, ' ')
    .trim();
}

/**
 * Whether any of the given fields contains the term, ignoring case, accents and punctuation.
 *
 * Fields are searched separately rather than joined, so a term cannot match across the boundary
 * between two of them — "za pr" should not find an institution merely because its country ends
 * where its provider number begins.
 *
 * @param term the text the user typed
 * @param fields the values to look in
 * @returns true when the term is empty, or when some field contains it
 */
export function matchesFilter(term: string, ...fields: (string | null | undefined)[]): boolean {
  const needle = fold(term);
  if (!needle) {
    return true;
  }
  return fields.some((field) => fold(field).includes(needle));
}
