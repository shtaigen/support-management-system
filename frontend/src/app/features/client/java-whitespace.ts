// Java's Character.isWhitespace: U+0009-U+000D, U+001C-U+001F and Zs/Zl/Zp, except the
// non-breaking spaces U+00A0, U+2007 and U+202F. U+FEFF is not whitespace (ADR-015 §5).
const JAVA_BLANK = /^(?:(?![   ])[\u0009-\u000D\u001C-\u001F\p{Zs}\p{Zl}\p{Zp}])*$/u;
const JAVA_WHITESPACE = /^(?![   ])[\u0009-\u000D\u001C-\u001F\p{Zs}\p{Zl}\p{Zp}]$/u;

// String.isBlank() as the backend uses it (NotBlankTextValidator), never JavaScript's trim().
export function isJavaBlank(text: string): boolean {
  return JAVA_BLANK.test(text);
}

// String.strip() as the backend uses it (CreateTicketRequest), never JavaScript's trim().
// Every Java whitespace character is in the BMP, so testing single UTF-16 code units is enough.
export function javaStrip(text: string): string {
  let start = 0;
  let end = text.length;
  while (start < end && JAVA_WHITESPACE.test(text[start])) {
    start++;
  }
  while (end > start && JAVA_WHITESPACE.test(text[end - 1])) {
    end--;
  }
  return text.slice(start, end);
}
