import { HttpErrorResponse } from '@angular/common/http';
import { GENERIC_ERROR_MESSAGE, UNREACHABLE_MESSAGE, toProblem } from './problem-detail';

describe('toProblem', () => {
  it('returns a real ProblemDetail as is', () => {
    const body = {
      status: 401,
      title: 'Unauthorized',
      detail: 'Invalid email or password.',
    };
    expect(toProblem(new HttpErrorResponse({ status: 401, error: body }))).toEqual(body);
  });

  it('reports an unreachable server for status 0', () => {
    expect(
      toProblem(new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') })),
    ).toEqual({ status: 0, title: UNREACHABLE_MESSAGE, detail: UNREACHABLE_MESSAGE });
  });

  it('falls back to the generic message for an HTML body', () => {
    const error = new HttpErrorResponse({ status: 502, error: '<html>Bad Gateway</html>' });
    expect(toProblem(error)).toEqual({
      status: 502,
      title: GENERIC_ERROR_MESSAGE,
      detail: GENERIC_ERROR_MESSAGE,
    });
  });

  it('falls back to the generic message for a null body', () => {
    const error = new HttpErrorResponse({ status: 500, error: null });
    expect(toProblem(error)).toEqual({
      status: 500,
      title: GENERIC_ERROR_MESSAGE,
      detail: GENERIC_ERROR_MESSAGE,
    });
  });
});
