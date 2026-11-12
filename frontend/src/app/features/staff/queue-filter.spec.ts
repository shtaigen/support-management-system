import { TicketStatus } from '../../shared/ticket/ticket';
import { toTicketFilter } from './queue-filter';

describe('toTicketFilter', () => {
  it('maps Active to every status except CLOSED, in order', () => {
    expect(toTicketFilter('active', 'anyone')).toStrictEqual({
      statuses: ['OPEN', 'ANSWERED', 'CUSTOMER_REPLIED'],
    });
  });

  it('maps All to no status', () => {
    expect(toTicketFilter('all', 'anyone')).toStrictEqual({ statuses: [] });
  });

  it('maps a single status to that status', () => {
    for (const status of ['OPEN', 'ANSWERED', 'CUSTOMER_REPLIED', 'CLOSED'] satisfies TicketStatus[]) {
      expect(toTicketFilter(status, 'anyone')).toStrictEqual({ statuses: [status] });
    }
  });

  it('maps Anyone to neither an assignee nor unassigned', () => {
    expect(toTicketFilter('all', 'anyone')).toStrictEqual({ statuses: [] });
  });

  it('maps Unassigned to unassigned=true', () => {
    expect(toTicketFilter('all', 'unassigned')).toStrictEqual({ statuses: [], unassigned: true });
  });

  it('maps an agent key to its id', () => {
    expect(toTicketFilter('all', 'agent:7')).toStrictEqual({ statuses: [], assigneeId: 7 });
  });

  it('passes the customer id through as the same string', () => {
    expect(toTicketFilter('all', 'anyone', '9007199254740993')).toStrictEqual({
      statuses: [],
      customerId: '9007199254740993',
    });
  });

  it('combines all keys', () => {
    expect(toTicketFilter('CLOSED', 'agent:3', '42')).toStrictEqual({
      statuses: ['CLOSED'],
      assigneeId: 3,
      customerId: '42',
    });
  });
});
