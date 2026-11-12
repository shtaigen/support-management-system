import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MemoryStorage } from '../../core/auth/memory-storage';
import { SESSION_STORAGE } from '../../core/auth/session';
import { AGENTS_URL, AGENT_CUSTOMERS_URL, AGENT_TICKETS_URL, AgentApi } from './agent-api';
import { AgentTicketFilter } from './agent-ticket';

describe('AgentApi', () => {
  let api: AgentApi;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SESSION_STORAGE, useValue: new MemoryStorage() },
      ],
    });
    api = TestBed.inject(AgentApi);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  // urlWithParams shows the order and the repetition of the parameters (ADR-016 Tests).
  function expectTickets(filter: AgentTicketFilter): string {
    api.tickets(filter).subscribe();
    const req = httpTesting.expectOne((r) => r.method === 'GET' && r.url === AGENT_TICKETS_URL);
    req.flush([]);
    return req.request.urlWithParams;
  }

  it('lists tickets without parameters for an empty status list', () => {
    expect(expectTickets({ statuses: [] })).toBe('/api/agent/tickets');
  });

  it('sends one status parameter per value in the given order', () => {
    expect(expectTickets({ statuses: ['OPEN', 'ANSWERED', 'CUSTOMER_REPLIED'] })).toBe(
      '/api/agent/tickets?status=OPEN&status=ANSWERED&status=CUSTOMER_REPLIED',
    );
  });

  it('sends no unassigned parameter when it is false', () => {
    expect(expectTickets({ statuses: [], unassigned: false })).toBe('/api/agent/tickets');
  });

  it('sends no unassigned parameter when it is absent', () => {
    expect(expectTickets({ statuses: ['OPEN'] })).toBe('/api/agent/tickets?status=OPEN');
  });

  it('sends unassigned=true when it is true', () => {
    expect(expectTickets({ statuses: [], unassigned: true })).toBe(
      '/api/agent/tickets?unassigned=true',
    );
  });

  it('sends the assignee id', () => {
    expect(expectTickets({ statuses: [], assigneeId: 5 })).toBe('/api/agent/tickets?assigneeId=5');
  });

  it('sends a customer id beyond Number.MAX_SAFE_INTEGER unchanged', () => {
    // A conversion to a number would send ...992.
    expect(expectTickets({ statuses: [], customerId: '9007199254740993' })).toBe(
      '/api/agent/tickets?customerId=9007199254740993',
    );
  });

  it('lists customers', () => {
    api.customers().subscribe();
    httpTesting.expectOne({ method: 'GET', url: AGENT_CUSTOMERS_URL }).flush([]);
    expect(AGENT_CUSTOMERS_URL).toBe('/api/agent/customers');
  });

  it('lists agents', () => {
    api.agents().subscribe();
    httpTesting.expectOne({ method: 'GET', url: AGENTS_URL }).flush([]);
    expect(AGENTS_URL).toBe('/api/agent/agents');
  });
});
