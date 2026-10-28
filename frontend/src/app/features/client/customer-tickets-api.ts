import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CreateTicketRequest,
  CustomerTicketResponse,
  CustomerTicketSummary,
  TicketStatus,
} from './customer-ticket';

export const TICKETS_URL = '/api/customer/tickets';

@Injectable({ providedIn: 'root' })
export class CustomerTicketsApi {
  private readonly http = inject(HttpClient);

  // Without a status no parameter is sent at all: the server then returns every status.
  list(status?: TicketStatus): Observable<CustomerTicketSummary[]> {
    return this.http.get<CustomerTicketSummary[]>(TICKETS_URL, {
      params: status ? { status } : {},
    });
  }

  get(id: string): Observable<CustomerTicketResponse> {
    return this.http.get<CustomerTicketResponse>(ticketUrl(id));
  }

  create(request: CreateTicketRequest): Observable<CustomerTicketResponse> {
    return this.http.post<CustomerTicketResponse>(TICKETS_URL, request);
  }

  postMessage(id: string, body: string): Observable<CustomerTicketResponse> {
    return this.http.post<CustomerTicketResponse>(`${ticketUrl(id)}/messages`, { body });
  }
}

// The id stays a string: a Java long can exceed Number.MAX_SAFE_INTEGER (ADR-015 §3).
function ticketUrl(id: string): string {
  return `${TICKETS_URL}/${encodeURIComponent(id)}`;
}
