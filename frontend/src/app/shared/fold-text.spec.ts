import { fold, matchesFilter } from './fold-text';

/**
 * The folding rule the client-side filters rely on.
 *
 * These are the cases that decide whether a filter feels broken. Somebody typing a surname in
 * capitals, or typing "renee" for a graduate whose name carries an accent, is doing the ordinary
 * thing — a filter that returns nothing there reads as "this person is not in the register",
 * which is the most misleading answer this console can give.
 */
describe('fold', () => {
  it('lower-cases, so capitals match', () => {
    expect(fold('MAHLANGU')).toBe('mahlangu');
    expect(fold('Mahlangu')).toBe('mahlangu');
  });

  it('strips accents, so "renee" and "Renée" are the same text', () => {
    expect(fold('Renée')).toBe('renee');
    expect(fold('renee')).toBe('renee');
  });

  it('drops punctuation and collapses the gaps it leaves', () => {
    expect(fold('Thandeka N. Mahlangu')).toBe('thandeka n mahlangu');
    expect(fold('  PR-0142  ')).toBe('pr 0142');
  });

  it('keeps digits, because provider numbers and NQF levels are searched too', () => {
    expect(fold('inst-11111111-2020-01')).toBe('inst 11111111 2020 01');
  });

  it('is empty for nothing at all', () => {
    expect(fold(null)).toBe('');
    expect(fold(undefined)).toBe('');
    expect(fold('   ')).toBe('');
  });
});

describe('matchesFilter', () => {
  it('matches regardless of case', () => {
    expect(matchesFilter('example', 'Example University')).toBeTrue();
    expect(matchesFilter('EXAMPLE', 'Example University')).toBeTrue();
    expect(matchesFilter('ExAmPlE', 'Example University')).toBeTrue();
  });

  it('matches regardless of accents', () => {
    expect(matchesFilter('renee', 'Renée Botha')).toBeTrue();
    expect(matchesFilter('Renée', 'renee botha')).toBeTrue();
  });

  it('matches part of a word, because people type the part they remember', () => {
    expect(matchesFilter('hlang', 'Thandeka N. Mahlangu')).toBeTrue();
  });

  it('looks in every field it is given', () => {
    expect(matchesFilter('PR-0142', 'Example University', 'ZW', 'PR-0142')).toBeTrue();
    expect(matchesFilter('zw', 'Example University', 'ZW', 'PR-0142')).toBeTrue();
  });

  it('does not match across the boundary between two fields', () => {
    // Fields are searched separately on purpose. Joining them first would let "zw pr" match an
    // institution merely because its country ends where its provider number begins, which is a
    // match no user could predict or reproduce.
    expect(matchesFilter('zw pr', 'Example University', 'ZW', 'PR-0142')).toBeFalse();
  });

  it('matches everything when nothing has been typed', () => {
    expect(matchesFilter('', 'Example University')).toBeTrue();
    expect(matchesFilter('   ', 'Example University')).toBeTrue();
  });

  it('tolerates fields that are absent', () => {
    // An institution with no signing key has a null there, and filtering must not throw on it.
    expect(matchesFilter('example', 'Example University', null, undefined)).toBeTrue();
    expect(matchesFilter('missing', null, undefined)).toBeFalse();
  });
});
