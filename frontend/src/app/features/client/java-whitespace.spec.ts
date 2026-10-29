import { isJavaBlank } from './java-whitespace';

describe('isJavaBlank', () => {
  it('treats Java whitespace as blank', () => {
    expect(isJavaBlank(' \t\n\r\u000B\f')).toBe(true);
    expect(isJavaBlank('\u001C')).toBe(true);
    expect(isJavaBlank('\u2028')).toBe(true);
    expect(isJavaBlank('\u3000')).toBe(true);
  });

  it('does not treat U+00A0, U+2007, U+202F and U+FEFF as blank', () => {
    expect(isJavaBlank('\u00A0')).toBe(false);
    expect(isJavaBlank('\u2007')).toBe(false);
    expect(isJavaBlank('\u202F')).toBe(false);
    expect(isJavaBlank('\uFEFF')).toBe(false);
  });

  it('treats the empty string as blank', () => {
    expect(isJavaBlank('')).toBe(true);
  });

  it('does not treat text surrounded by whitespace as blank', () => {
    expect(isJavaBlank('  a  ')).toBe(false);
  });
});
