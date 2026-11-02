import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { MemoryStorage } from '../../core/auth/memory-storage';
import { SESSION_STORAGE } from '../../core/auth/session';
import { CustomerTicketResponse } from './customer-ticket';
import { TICKETS_URL } from './customer-tickets-api';
import { NewTicket } from './new-ticket';

const CREATED: CustomerTicketResponse = {
  id: 42,
  subject: 'Printer broken',
  category: 'CLAIM',
  referenceNumber: 'ABC-1',
  status: 'OPEN',
  createdAt: '2026-11-01T12:00:00Z',
  lastMessageAt: '2026-11-01T12:00:00Z',
  messages: [
    { id: 1, body: 'It is broken', createdAt: '2026-11-01T12:00:00Z', authorRole: 'CUSTOMER' },
  ],
};

// A live 400 from POST /api/customer/tickets: not in form order, two entries for one field.
const LIVE_ERRORS = [
  { field: 'category', message: 'must not be null' },
  { field: 'subject', message: 'must not be blank' },
  { field: 'referenceNumber', message: 'size must be between 0 and 50' },
  { field: 'message', message: 'must not be blank' },
  { field: 'referenceNumber', message: 'may contain only letters, digits and hyphens' },
];

type Field = 'subject' | 'category' | 'referenceNumber' | 'message';

const CONTROL_SELECTORS: Record<Exclude<Field, 'category'>, string> = {
  subject: '.subject input',
  referenceNumber: '.reference-number input',
  message: '.message textarea',
};

const FIELD_CLASSES: Record<Field, string> = {
  subject: '.subject',
  category: '.category',
  referenceNumber: '.reference-number',
  message: '.message',
};

describe('NewTicket', () => {
  let harness: RouterTestingHarness;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'client/tickets/new', component: NewTicket }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SESSION_STORAGE, useValue: new MemoryStorage() },
      ],
    });
    harness = await RouterTestingHarness.create();
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  function root(): HTMLElement {
    return harness.routeNativeElement!;
  }

  function render(): void {
    harness.detectChanges();
  }

  async function open(): Promise<NewTicket> {
    return harness.navigateByUrl('/client/tickets/new', NewTicket);
  }

  function control(field: Exclude<Field, 'category'>): HTMLInputElement {
    return root().querySelector<HTMLInputElement>(CONTROL_SELECTORS[field])!;
  }

  function select(): HTMLElement {
    return root().querySelector<HTMLElement>('mat-select')!;
  }

  function type(field: Exclude<Field, 'category'>, value: string): void {
    const input = control(field);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    render();
  }

  // Opens the panel and clicks the option, as a user does.
  function chooseCategory(label: string): void {
    root().querySelector<HTMLElement>('.mat-mdc-select-trigger')!.click();
    render();
    const option = [...document.querySelectorAll<HTMLElement>('mat-option')].find(
      (o) => o.textContent!.trim() === label,
    )!;
    option.click();
    render();
  }

  // A real focus change: jsdom fires blur on the element that loses focus.
  function moveFocus(from: HTMLElement, to: HTMLElement): void {
    from.focus();
    to.focus();
    render();
  }

  function fillValid(values: Partial<Record<Exclude<Field, 'category'>, string>> = {}): void {
    type('subject', values.subject ?? 'Printer broken');
    chooseCategory('Claim');
    type('referenceNumber', values.referenceNumber ?? 'ABC-1');
    type('message', values.message ?? 'It is broken');
  }

  function createButton(): HTMLButtonElement {
    return root().querySelector<HTMLButtonElement>('button[type=submit]')!;
  }

  // Submits through the form, as a user does: unlike submit(), this marks the form submitted.
  function clickCreate(): void {
    createButton().click();
    render();
  }

  function expectCreate(): TestRequest {
    return httpTesting.expectOne({ method: 'POST', url: TICKETS_URL });
  }

  function expectNoCreate(): void {
    httpTesting.expectNone({ method: 'POST', url: TICKETS_URL });
  }

  // Answers a sent request with a 5xx, which only shows a message above the form.
  function expectSent(): void {
    fail(expectCreate(), 500);
  }

  function fail(req: TestRequest, status: number, body: object = {}): void {
    req.flush(
      { status, title: 'Failure', detail: `Failure ${status}.`, ...body },
      { status, statusText: 'Failure' },
    );
    render();
  }

  function failValidation(req: TestRequest, errors: { field: string; message: string }[]): void {
    fail(req, 400, { detail: 'Validation failed.', errors });
  }

  function formField(field: Field): HTMLElement {
    return root().querySelector<HTMLElement>(`mat-form-field${FIELD_CLASSES[field]}`)!;
  }

  function errorOf(field: Field): string | null {
    return formField(field).querySelector('mat-error')?.textContent!.trim() ?? null;
  }

  function formError(): string | null {
    return root().querySelector('.form-error')?.textContent!.trim() ?? null;
  }

  describe('form', () => {
    it('server errors land on their controls regardless of order', async () => {
      await open();
      fillValid();
      clickCreate();
      failValidation(expectCreate(), LIVE_ERRORS);

      expect(errorOf('subject')).toBe('must not be blank');
      expect(errorOf('category')).toBe('must not be null');
      expect(errorOf('message')).toBe('must not be blank');
      expect(formError()).toBeNull();
    });

    it('two messages for one field are both shown', async () => {
      await open();
      fillValid();
      clickCreate();
      failValidation(expectCreate(), [
        { field: 'referenceNumber', message: 'size must be between 0 and 50' },
        { field: 'subject', message: 'must not be blank' },
        { field: 'referenceNumber', message: 'may contain only letters, digits and hyphens' },
      ]);

      expect(errorOf('referenceNumber')).toBe(
        'size must be between 0 and 50; may contain only letters, digits and hyphens',
      );
    });

    it('an unknown field shows the message above the form', async () => {
      await open();
      fillValid();
      clickCreate();
      failValidation(expectCreate(), [
        { field: 'priority', message: 'is unknown' },
        { field: 'subject', message: 'must not be blank' },
      ]);

      expect(formError()).toBe('Validation failed.');
      expect(root().querySelector('.form-error')!.getAttribute('role')).toBe('alert');
      expect(errorOf('subject')).toBe('must not be blank');
    });

    it('the field constructor shows the message above the form', async () => {
      await open();
      fillValid();
      clickCreate();
      failValidation(expectCreate(), [{ field: 'constructor', message: 'is unknown' }]);

      expect(formError()).toBe('Validation failed.');
      expect(createButton().disabled).toBe(false);
    });

    it('a 400 without errors shows its detail above the form', async () => {
      await open();
      fillValid();
      clickCreate();
      fail(expectCreate(), 400, { detail: 'Failed to read request' });

      expect(formError()).toBe('Failed to read request');
    });

    it('a 400 with an empty errors array shows the message above the form', async () => {
      await open();
      fillValid();
      clickCreate();
      failValidation(expectCreate(), []);

      expect(formError()).toBe('Validation failed.');
      expect(root().querySelector('.form-error')!.getAttribute('role')).toBe('alert');
    });

    it('the message above the form disappears on the next submit', async () => {
      await open();
      fillValid();
      clickCreate();
      fail(expectCreate(), 500);
      expect(formError()).toBe('Failure 500.');

      clickCreate();
      expect(formError()).toBeNull();
      expectSent();
    });

    it('a 401 shows no message and enables the button again', async () => {
      await open();
      fillValid();
      clickCreate();
      fail(expectCreate(), 401);

      expect(root().querySelector('[role=alert]')).toBeNull();
      expect(createButton().disabled).toBe(false);
    });

    it('a server error clears when its control changes', async () => {
      await open();
      fillValid();
      clickCreate();
      failValidation(expectCreate(), LIVE_ERRORS);
      expect(errorOf('subject')).toBe('must not be blank');

      type('subject', 'Printer still broken');
      expect(errorOf('subject')).toBeNull();
      expect(formField('subject').classList).not.toContain('mat-form-field-invalid');
      expect(errorOf('message')).toBe('must not be blank');
    });

    it('a double submit sends one request', async () => {
      const page = await open();
      fillValid();
      // Direct calls, not clicks: the disabled button would hide a missing guard.
      page.submit();
      page.submit();
      expectSent();
    });

    it('navigates to the new thread with replaceUrl on success', async () => {
      await open();
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      fillValid();
      clickCreate();
      expectCreate().flush(CREATED);

      expect(navigate).toHaveBeenCalledExactlyOnceWith('/client/tickets/42', { replaceUrl: true });
    });

    it('sends the values as typed', async () => {
      await open();
      fillValid({ subject: '  Printer broken  ', referenceNumber: '  ', message: ' It is broken ' });
      clickCreate();

      const req = expectCreate();
      expect(req.request.body).toEqual({
        subject: '  Printer broken  ',
        category: 'CLAIM',
        referenceNumber: '  ',
        message: ' It is broken ',
      });
      fail(req, 500);
    });

    describe('boundaries', () => {
      it('a subject of 200 characters is valid, 201 is not', async () => {
        await open();
        fillValid({ subject: 'a'.repeat(201) });
        clickCreate();
        expectNoCreate();
        expect(errorOf('subject')).toBe('Use at most 200 characters.');

        type('subject', 'a'.repeat(200));
        clickCreate();
        expectSent();
      });

      it('a subject of 200 characters plus surrounding whitespace is valid', async () => {
        await open();
        // U+001C is whitespace in Java but not for trim(); trim() removes U+2028 too.
        fillValid({ subject: ' \u001C' + 'a'.repeat(200) + '  ' });
        clickCreate();

        expect(errorOf('subject')).toBeNull();
        expectSent();
      });

      it('a message of 4000 characters is valid, 4001 is not', async () => {
        await open();
        fillValid({ message: 'a'.repeat(4001) });
        clickCreate();
        expectNoCreate();
        expect(errorOf('message')).toBe('Use at most 4000 characters.');

        type('message', 'a'.repeat(4000));
        clickCreate();
        expectSent();
      });

      it('a message of 4000 characters plus a trailing space is invalid', async () => {
        await open();
        fillValid({ message: 'a'.repeat(4000) + ' ' });
        clickCreate();

        expectNoCreate();
        expect(errorOf('message')).toBe('Use at most 4000 characters.');
      });

      it('a reference number of 50 characters after stripping is valid', async () => {
        await open();
        fillValid({ referenceNumber: ' \u001C' + 'A'.repeat(50) + '\u001C ' });
        clickCreate();

        expect(errorOf('referenceNumber')).toBeNull();
        expectSent();
      });

      it('a reference number of 51 characters is invalid', async () => {
        await open();
        fillValid({ referenceNumber: 'A'.repeat(51) });
        clickCreate();

        expectNoCreate();
        expect(errorOf('referenceNumber')).toBe('Use 1–50 letters, digits or hyphens.');
      });

      for (const [name, value] of [
        ['an empty', ''],
        ['a blank', ' \t'],
      ]) {
        it(`${name} reference number is valid`, async () => {
          await open();
          fillValid({ referenceNumber: value });
          clickCreate();

          expect(errorOf('referenceNumber')).toBeNull();
          const req = expectCreate();
          expect(req.request.body.referenceNumber).toBe(value);
          fail(req, 500);
        });
      }

      it('a subject of only U+00A0 is valid', async () => {
        await open();
        fillValid({ subject: ' ' });
        clickCreate();

        expect(errorOf('subject')).toBeNull();
        expectSent();
      });

      it('a subject of only U+001C is invalid', async () => {
        await open();
        fillValid({ subject: '\u001C' });
        clickCreate();

        expectNoCreate();
        expect(errorOf('subject')).toBe('Enter a subject.');
      });
    });
  });

  describe('error display', () => {
    it('an invalid field shows no error before it loses focus', async () => {
      await open();
      control('subject').focus();
      type('subject', '   ');

      expect(errorOf('subject')).toBeNull();
      expect(formField('subject').classList).not.toContain('mat-form-field-invalid');
      expect(control('subject').getAttribute('aria-invalid')).toBe('false');
    });

    it('an input shows its error after losing focus', async () => {
      await open();
      moveFocus(control('subject'), control('message'));

      expect(errorOf('subject')).toBe('Enter a subject.');
      expect(formField('subject').classList).toContain('mat-form-field-invalid');
      expect(errorOf('message')).toBeNull();
    });

    it('the category shows its error after losing focus', async () => {
      await open();
      moveFocus(select(), control('subject'));

      expect(errorOf('category')).toBe('Choose a category.');
      expect(select().getAttribute('aria-invalid')).toBe('true');
    });

    it('the button is enabled while the form is invalid', async () => {
      await open();
      render();

      expect(createButton().disabled).toBe(false);
    });

    it('a submit of the empty form sends nothing and shows every error', async () => {
      await open();
      clickCreate();

      expectNoCreate();
      expect(errorOf('subject')).toBe('Enter a subject.');
      expect(errorOf('category')).toBe('Choose a category.');
      expect(errorOf('referenceNumber')).toBeNull();
      expect(errorOf('message')).toBe('Enter a message.');
    });

    it('a server error is shown instead of a client error', async () => {
      await open();
      // NUL is not checked on the client (ADR-015 §5); the server rejects it.
      fillValid({ subject: 'a\u0000b' });
      clickCreate();
      failValidation(expectCreate(), [
        { field: 'subject', message: 'must not contain NUL characters' },
      ]);
      expect(errorOf('subject')).toBe('must not contain NUL characters');

      type('subject', '');
      expect(errorOf('subject')).toBe('Enter a subject.');
    });

    it('the button is disabled while submitting', async () => {
      await open();
      fillValid();
      clickCreate();
      const req = expectCreate();
      expect(createButton().disabled).toBe(true);

      fail(req, 500);
      expect(createButton().disabled).toBe(false);
    });
  });
});
