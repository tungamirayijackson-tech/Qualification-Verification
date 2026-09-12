/**
 * The shape of a Zimbabwean national identity number, as the console understands it.
 *
 * Mirrors `NationalId` on the server, and deliberately only the *shape*: two digits for the
 * registration office, six or seven for the serial, a check letter, two for the district of
 * origin — written with hyphens, with spaces, or with neither. The check letter is not
 * recomputed here for the same reason it is not recomputed there: refusing a genuine id because
 * a published description of the arithmetic was wrong is worse than accepting a mistyped one.
 *
 * The console never hashes anything. This exists so a lookup fires when an id is complete rather
 * than when it happens to reach a particular length, and so the field can say what it wants
 * before the request is made.
 */
const SHAPE = /^ *\d{2} *[- ]? *\d{6,7} *[- ]? *[A-Za-z] *[- ]? *\d{2} *$/;

/**
 * Whether this is shaped like a national id.
 *
 * @param value as typed, punctuation and all
 */
export function isWellFormedNationalId(value: string): boolean {
  return SHAPE.test(value);
}
