import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AgentCustomerSummary, AgentRef, AgentTicketFilter, AgentTicketSummary } from './agent-ticket';

export const AGENT_TICKETS_URL = '/api/agent/tickets';
export const AGENT_CUSTOMERS_URL = '/api/agent/customers';
export const AGENTS_URL = '/api/agent/agents';

@Injectable({ providedIn: 'root' })
export class AgentApi {
  private readonly http = inject(HttpClient);

  tickets(filter: AgentTicketFilter): Observable<AgentTicketSummary[]> {
    // One status parameter per value, in the given order (the repeated form of FR-04).
    let params = new HttpParams();
    for (const status of filter.statuses) {
      params = params.append('status', status);
    }
    if (filter.assigneeId !== undefined) {
      params = params.append('assigneeId', filter.assigneeId);
    }
    if (filter.unassigned === true) {
      params = params.append('unassigned', true);
    }
    if (filter.customerId !== undefined) {
      params = params.append('customerId', filter.customerId);
    }
    return this.http.get<AgentTicketSummary[]>(AGENT_TICKETS_URL, { params });
  }

  customers(): Observable<AgentCustomerSummary[]> {
    return this.http.get<AgentCustomerSummary[]>(AGENT_CUSTOMERS_URL);
  }

  agents(): Observable<AgentRef[]> {
    return this.http.get<AgentRef[]>(AGENTS_URL);
  }
}
