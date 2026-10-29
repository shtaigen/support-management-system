// Java's Character.isWhitespace: U+0009-U+000D, U+001C-U+001F and Zs/Zl/Zp, except the
// non-breaking spaces U+00A0, U+2007 and U+202F. U+FEFF is not whitespace (ADR-015 §5).
const JAVA_BLANK = /^(?:(?![\u00A0\u2007\u202F])[\u0009-\u000D\u001C-\u001F\p{Zs}\p{Zl}\p{Zp}])*$/u;

// String.isBlank() as the backend uses it (NotBlankTextValidator), never JavaScript's trim().
export function isJavaBlank(text: string): boolean {
  return JAVA_BLANK.test(text);
}
