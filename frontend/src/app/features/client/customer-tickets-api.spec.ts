import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MemoryStorage } from '../../core/auth/memory-storage';
import { SESSION_STORAGE } from '../../core/auth/session';
import { CreateTicketRequest } from './customer-ticket';
import { CustomerTicketsApi, TICKETS_URL } from './customer-tickets-api';

describe('CustomerTicketsApi', () => {
  let api: CustomerTicketsApi;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SESSION_STORAGE, useValue: new MemoryStorage() },
      ],
    });
    api = TestBed.inject(CustomerTicketsApi);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('lists without a status parameter', () => {
    api.list().subscribe();
    const req = httpTesting.expectOne({ method: 'GET', url: TICKETS_URL });
    expect(req.request.params.keys()).toEqual([]);
    expect(req.request.urlWithParams).toBe('/api/customer/tickets');
    req.flush([]);
  });

  it('lists with a status parameter', () => {
    api.list('OPEN').subscribe();
    const req = httpTesting.expectOne((r) => r.method === 'GET' && r.url === TICKETS_URL);
    expect(req.request.params.getAll('status')).toEqual(['OPEN']);
    req.flush([]);
  });

  it('gets a ticket with an id beyond Number.MAX_SAFE_INTEGER unchanged', () => {
    api.get('9007199254740993').subscribe();
    httpTesting
      .expectOne({ method: 'GET', url: '/api/customer/tickets/9007199254740993' })
      .flush({});
  });

  it('encodes the id in the URL', () => {
    api.get('a/b').subscribe();
    httpTesting.expectOne({ method: 'GET', url: '/api/customer/tickets/a%2Fb' }).flush({});
  });

  it('creates a ticket', () => {
    const request: CreateTicketRequest = {
      subject: 'Printer broken',
      category: 'GENERAL',
      referenceNumber: '',
      message: 'It does not print.',
    };
    api.create(request).subscribe();
    const req = httpTesting.expectOne({ method: 'POST', url: TICKETS_URL });
    expect(req.request.body).toEqual(request);
    req.flush({});
  });

  it('posts a message', () => {
    api.postMessage('7', 'Hi').subscribe();
    const req = httpTesting.expectOne({ method: 'POST', url: '/api/customer/tickets/7/messages' });
    expect(req.request.body).toEqual({ body: 'Hi' });
    req.flush({});
  });
});
