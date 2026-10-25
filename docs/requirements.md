# Requirements

## Purpose and source

This document is the functional baseline for the MVP of the support management system:
customers open tickets and exchange messages with agents, and agents manage tickets by status
and assignee. Requirement IDs (`TR-`, `FR-`, `BR-`, `NFR-`, `OQ-`) are stable, so code and
tests can reference them.

Sources:
- **EI**: an earlier implementation of the same domain. It was used only to learn the expected
  behaviour, never as a design or code template.
- **PD**: a project decision. These are the ADRs in `docs/decisions.md`, the Flyway V1 schema
  (`V1__initial_schema.sql`), or decisions agreed in the requirements review
  ("agreed in review").

Every requirement for our system has a Source. When behaviour from the earlier implementation
explains a requirement, it is given as rationale only.

---

## 1. Actors and roles

The roles are exactly `CUSTOMER` and `AGENT` (V1 `users_role_ck`, ADR-011).

| Role | Can see | Can do | Source |
|---|---|---|---|
| CUSTOMER | Only their own tickets and the messages in them. Not the assignee. | Create a ticket; post a message to an own ticket, which also reopens a closed one. | EI, PD (ADR-009, agreed in review) |
| AGENT | All tickets of all customers; the customer's name and email; the assignee. | Reply to tickets; change status manually (close, reopen); assign and reassign tickets. | EI, PD (agreed in review) |

Data visibility:
- A customer's access is limited to their own tickets in the database query itself, not by
  filtering results after they are loaded (ADR-009).
- Agents are not restricted by assignment. Every agent sees every ticket.
- Each role has its own UI zone. A user who opens the other role's zone is redirected to their
  own zone (ADR-011).

---

## 2. Ticket lifecycle

### Statuses (V1 `tickets_status_ck`)

| Status | Meaning |
|---|---|
| OPEN | Newly created, or reopened by an agent. Waiting for an agent reply. |
| ANSWERED | An agent wrote the last message. |
| CUSTOMER_REPLIED | The customer replied after an agent, or reopened a closed ticket by posting a message. |
| CLOSED | Closed by an agent. |

### Allowed transitions

| ID | From | To | Who | Trigger | Preconditions | Side effects |
|---|---|---|---|---|---|---|
| TR-01 | (none) | OPEN | Customer | Creates a ticket (FR-03) | Valid subject, category, optional reference number and first message | Ticket and first message created in one transaction; `last_message_at` set |
| TR-02 | OPEN, ANSWERED, CUSTOMER_REPLIED | ANSWERED | Agent | Posts a message (FR-10) | Ticket is not CLOSED | Auto-assign to this agent if unassigned (BR-09); `last_message_at` set |
| TR-03 | ANSWERED | CUSTOMER_REPLIED | Customer | Posts a message (FR-06) | Owns the ticket | `last_message_at` set |
| TR-04 | OPEN | OPEN (unchanged) | Customer | Posts a message (FR-06) | Owns the ticket | `last_message_at` set |
| TR-05 | CUSTOMER_REPLIED | CUSTOMER_REPLIED (unchanged) | Customer | Posts a message (FR-06) | Owns the ticket | `last_message_at` set |
| TR-06 | CLOSED | CUSTOMER_REPLIED | Customer | Posts a message (FR-06) | Owns the ticket | Ticket reopened; assignee kept; `last_message_at` set |
| TR-07 | OPEN, ANSWERED, CUSTOMER_REPLIED | CLOSED | Agent | Manual status change (FR-11) | Ticket is not already CLOSED | Assignee kept |
| TR-08 | CLOSED | OPEN | Agent | Manual status change (FR-11) | Ticket is CLOSED | Assignee kept |

An agent message on an ANSWERED ticket leaves the status at ANSWERED.

### Forbidden transitions

Every invalid transition returns **409 Conflict** (PD, agreed in review).

| Attempt | Result |
|---|---|
| Agent posts a message to a CLOSED ticket | 409 |
| Manual change to the ticket's current status (for example CLOSED → CLOSED) | 409 |
| Manual change to ANSWERED or CUSTOMER_REPLIED. Only messages set these statuses. | 409 |
| Manual change from ANSWERED or CUSTOMER_REPLIED to OPEN | 409 |
| Any status change by a customer, including closing | 403. Customers have no status-change operation, see BR-08. |

Rationale (EI): the earlier implementation had three states (new, open, closed). The first agent
reply moved a ticket out of "new". A customer message reopened a closed ticket. Only staff could
close a ticket.

---

## 3. Functional requirements

Common error responses, used by all FRs (details in NFR-03):
- **400**: validation failure, including a path or query value that cannot be converted, for
  example a non-numeric id (ADR-008).
- **401**: no token, or the token is invalid or expired.
- **403**: the role is not allowed to perform the operation.
- **404**: the ticket does not exist, or it belongs to another customer (BR-01).
- **409**: invalid transition (section 2) or an optimistic-locking conflict (BR-15). Each of the
  two causes has its own fixed `detail` text. The response never includes ids, versions or
  status values.

### FR-01 Log in
| Field | Value |
|---|---|
| Actor | Customer, Agent |
| Description | A user logs in with email and password. |
| Inputs | `email`: required, not blank, at most 320 characters, lowercased before lookup (BR-14). `password`: required. |
| Outcome | An access token (NFR-01) and the user's id, name and role. The client opens the zone for that role. |
| Errors | 400 when a field is missing. 401 when the credentials are invalid, with the same response for an unknown email and a wrong password. |
| Source | PD (V1, ADR-005, ADR-009). EI: separate login forms per role, replaced by one email + password login. |

### FR-02 Log out
| Field | Value |
|---|---|
| Actor | Customer, Agent |
| Description | The user ends the session on this client. |
| Inputs | None. |
| Outcome | The client discards the token and shows the login screen. There is no server-side revocation: a copied token stays valid until it expires (NFR-01). This is a known limitation. |
| Errors | None. |
| Source | PD (agreed in review) |

### FR-03 Create a ticket
| Field | Value |
|---|---|
| Actor | Customer |
| Description | The customer opens a new ticket with a first message. |
| Inputs | `subject`: required, not blank, at most 200 characters after trimming, no NUL (U+0000) characters. `category`: required, one of `GENERAL`, `POLICY`, `CLAIM`. `referenceNumber`: optional; a blank value counts as absent; if present, see BR-18. `message`: the first message body (BR-11). |
| Outcome | A ticket in status OPEN (TR-01), owned by the caller, unassigned, with the first message authored by the caller. Ticket and message are created in one transaction (BR-16). |
| Errors | 400 on validation failure. 403 when the caller is not a customer. |
| Source | EI (a ticket always starts with a message). PD (V1 columns and CHECKs; reference number format agreed in review). |

### FR-04 List own tickets
| Field | Value |
|---|---|
| Actor | Customer |
| Description | The customer sees a list of their own tickets. |
| Inputs | `status` (optional, repeatable): `?status=OPEN&status=CLOSED`. The comma form `?status=OPEN,CLOSED` is also accepted. Empty values are ignored (`?status=`, `?status=OPEN,`); if no value remains, all statuses are returned. Values are case-sensitive. An unknown or lowercase value gives 400 with `errors[{field: "status"}]`, without the rejected value. |
| Outcome | Only the caller's tickets, sorted by NFR-05. Each item shows id, subject, category, reference number, status, `created_at` and `last_message_at`. It does not show the assignee. |
| Errors | 400 when a status value is unknown. 403 when the caller is not a customer. |
| Source | EI (own tickets, newest activity first, status tabs). PD (V1 index, agreed in review). |

### FR-05 View an own ticket
| Field | Value |
|---|---|
| Actor | Customer |
| Description | The customer opens one of their tickets and reads the thread. |
| Inputs | Ticket id. |
| Outcome | Ticket details without the assignee, and all messages in chronological order (NFR-05). Each message shows its body, its time, and whether the author is the customer or an agent. |
| Errors | 400 when the id is not a valid number (ADR-008). 404 when the ticket is missing or not owned by the caller (BR-01). 403 when the caller is not a customer. |
| Source | EI, PD (ADR-009, agreed in review; 400 for a malformed id added in step 6a) |

### FR-06 Post a message as a customer
| Field | Value |
|---|---|
| Actor | Customer |
| Description | The customer adds a message to one of their tickets. |
| Inputs | Ticket id. `body` (BR-11). |
| Outcome | A message authored by the caller. The status changes according to TR-03..TR-06; on a CLOSED ticket this reopens it to CUSTOMER_REPLIED. `last_message_at` and `updated_at` are set. The response is the updated ticket view, as in FR-05, so the client sees the new status. |
| Errors | 400 on an invalid body. 404 when the ticket is missing or not owned. 409 when another change to the ticket was committed at the same time (BR-15); the request carries no version. 403 when the caller is not a customer. |
| Source | EI (customer message reopens a closed ticket). PD (target statuses, agreed in review; 403 added in step 6a). |

### FR-07 List all tickets as an agent
| Field | Value |
|---|---|
| Actor | Agent |
| Description | The agent sees the queue of all tickets. |
| Inputs | `status` (optional): the same form and rules as in FR-04. `assigneeId` (optional): a user id; only tickets assigned to that user. `unassigned` (optional boolean, standard Spring conversion: `true`, `on`, `yes`, `1` and `false`, `off`, `no`, `0`, in any case; an empty value is the same as absent): `true` returns only tickets without an assignee; `false` is the same as absent. `customerId` (optional): a user id; only that customer's tickets (used by FR-08). All filters combine with AND, so `assigneeId` together with `unassigned=true` returns an empty list. An `assigneeId` that is not an agent, or a `customerId` that is not a customer, returns an empty list, not an error. |
| Outcome | Matching tickets, sorted by NFR-05. Each item shows id, subject, category, reference number, status, the customer (id, name and email), the assignee (id and name, or none), and `last_message_at`. It does not show the version. |
| Errors | 400 when a filter value is invalid: an unknown or lowercase status, a malformed id, or an `unassigned` value outside the accepted boolean values. The response names the field and does not echo the value. 403 when the caller is not an agent. |
| Source | EI (all tickets, status filter). PD (V1 indexes; "unassigned" agreed in review; customer filter from the OQ-01 resolution; parameter names agreed before step 5c; accepted boolean values agreed in the step 5c plan review). |

### FR-08 View tickets grouped by customer
| Field | Value |
|---|---|
| Actor | Agent |
| Description | The agent sees a summary of customers who have tickets and drills down to one customer's tickets. |
| Inputs | `GET /api/agent/customers`, no parameters. The drill-down is FR-07 with the customer filter, so the FR-07 status and assignee filters are available there. |
| Outcome | The summary lists every customer with at least one ticket. Each item shows the customer id, name and email, `ticketCount` (all tickets), `activeTicketCount` (tickets not in CLOSED) and `lastMessageAt` (the latest `last_message_at` of the customer's tickets). Each item is flat: `id`, `name`, `email`, `ticketCount`, `activeTicketCount`, `lastMessageAt`. The response is a JSON array. Only users with role CUSTOMER are listed; the role is checked in the query, because the database does not check it (section 5). It is sorted by NFR-05. Selecting a customer shows that customer's tickets with the same per-ticket fields as FR-07. |
| Errors | 403 when the caller is not an agent. |
| Source | PD (agreed in review, MVP; shape resolved in OQ-01). Not present in EI. |

### FR-09 View any ticket as an agent
| Field | Value |
|---|---|
| Actor | Agent |
| Description | The agent opens any ticket. |
| Inputs | Ticket id. |
| Outcome | Ticket details: id, subject, category, reference number, status, the customer (id, name and email), the assignee (id and name, or none), `created_at`, `updated_at`, `last_message_at` and `version`. The version is what FR-11 and FR-12 send back for optimistic locking. The full thread in chronological order (NFR-05); each message shows its body, its time, and its author's name and role. |
| Errors | 400 when the id is not a valid number (ADR-008). 404 when the ticket does not exist. 403 when the caller is not an agent. |
| Source | EI, PD (version in the view agreed before step 5c; 400 for a malformed id added in step 6a) |

### FR-10 Reply as an agent
| Field | Value |
|---|---|
| Actor | Agent |
| Description | The agent adds a message to a ticket. |
| Inputs | Ticket id. `body` (BR-11). |
| Outcome | A message authored by the caller. The status becomes ANSWERED (TR-02). If the ticket was unassigned, the caller becomes the assignee (BR-09). A reply to a ticket assigned to another agent is allowed and keeps that assignee. `last_message_at` and `updated_at` are set. The response is the updated ticket view, as in FR-09. |
| Errors | 400 on an invalid body. 404 when the ticket does not exist. 409 when the ticket is CLOSED (BR-06), or when another change to the ticket was committed at the same time (BR-15); the request carries no version. 403 when the caller is not an agent. |
| Source | EI (first reply moves the ticket forward and auto-assigns). PD (agreed in review; no version and the response shape agreed before step 5c; 403 added in step 6a). |

### FR-11 Change status manually
| Field | Value |
|---|---|
| Actor | Agent |
| Description | The agent closes or reopens a ticket. |
| Inputs | Ticket id. `PUT /api/agent/tickets/{id}/status` with a JSON body. `status`: required, `CLOSED` or `OPEN`. `version`: required, the ticket version from the agent's FR-09 view (ADR-006). |
| Outcome | TR-07 (close) or TR-08 (reopen). The assignee is unchanged and `updated_at` is set. The response is the updated ticket view, as in FR-09, with the new version. |
| Errors | 400 when `status` or `version` is missing (`errors[{field}]`). 400 when the status value is unknown or not uppercase: the generic response for an unreadable body, without `errors[]` (ADR-008). 404 when the ticket does not exist. 409 with the optimistic-locking detail when `version` differs from the ticket's current version, or when another change was committed at the same time (BR-15). 409 with the transition detail on any transition not in TR-07/TR-08, including a change to the same status (BR-07). 403 when the caller is not an agent. Checks run in this order: request body, ticket, version, transition. |
| Source | EI (close only). PD (reopen and the transition rules, agreed in review; endpoint, version check and order of checks agreed before step 5d). |

### FR-12 Assign or reassign a ticket
| Field | Value |
|---|---|
| Actor | Agent |
| Description | The agent sets the ticket's assignee to themselves or to another agent. |
| Inputs | Ticket id. `PUT /api/agent/tickets/{id}/assignee` with a JSON body. `assigneeId`: required, an existing user with role AGENT. `version`: required, the ticket version from the agent's FR-09 view (ADR-006). |
| Outcome | The assignee is replaced; status and messages are unchanged; `updated_at` is set. Assigning the current assignee again is a no-op: 200, and `version` and `updated_at` do not change (the version is still checked). A CLOSED ticket can be assigned. Unassigning is not supported (BR-10). The response is the updated ticket view, as in FR-09. |
| Errors | 400 when `assigneeId` or `version` is missing (`errors[{field}]`). 400 with `errors[{field: "assigneeId"}]` when the user does not exist or is not an agent; both cases get the same message. Message: "must be an existing agent" (the same for a missing user, a customer and any other non-agent). 404 when the ticket does not exist. 409 with the optimistic-locking detail when `version` differs from the ticket's current version, or when another change was committed at the same time (BR-15). 403 when the caller is not an agent. Checks run in this order: request body, assignee, ticket, version. |
| Source | PD (agreed in review, MVP; endpoint, no-op reassignment, CLOSED tickets and order of checks agreed before step 5d). Not present in EI. |

## 4. Business rules

| ID | Rule | Source |
|---|---|---|
| BR-01 | A customer accesses only their own tickets. Ownership is part of the database query. Another customer's ticket returns 404, the same as a missing ticket. | EI, PD (ADR-009) |
| BR-02 | Operations are restricted by role. Calling an operation of the other role returns 403. | EI, PD |
| BR-03 | A new ticket starts as OPEN and unassigned. | PD (V1 default) |
| BR-04 | Messages drive the status changes TR-02..TR-06. ANSWERED and CUSTOMER_REPLIED are reached only through messages. | PD (agreed in review) |
| BR-05 | A customer message on a CLOSED ticket reopens it to CUSTOMER_REPLIED. | EI (reopen), PD (target status) |
| BR-06 | Agents cannot post to a CLOSED ticket (409). They reopen it first (TR-08). | PD (agreed in review) |
| BR-07 | Manual status changes are limited to TR-07 and TR-08. Every other manual transition, including a change to the same status, returns 409. | PD (agreed in review) |
| BR-08 | Customers cannot change a ticket's status, including closing it. | EI, PD |
| BR-09 | The first agent reply on an unassigned ticket assigns the replying agent. A reply never overwrites an existing assignee. | EI, PD |
| BR-10 | Agents can assign and reassign a ticket to any user with role AGENT, in any status, including CLOSED. Assigning the current assignee again changes nothing. Unassigning is not supported in the MVP. The assignee is kept when a ticket is closed or reopened. | PD (agreed in review; reassignment rules agreed before step 5d) |
| BR-11 | A message body is required, not blank, and at most 4000 characters counted as sent (surrounding whitespace included; the body is stored unchanged). It must not contain NUL (U+0000) characters, which PostgreSQL text columns cannot store. It is validated on the server. | PD (V1 `messages_body_not_blank_ck`, agreed in review) |
| BR-12 | Every message records its author (`author_id`). Whether the author is a customer or an agent follows from the author's role. | PD (V1) |
| BR-13 | Timestamps are stored as `timestamptz`. `created_at` is set on insert. `last_message_at` is set when the ticket is created and on every message. `updated_at` is set on every change to the ticket (status, assignee, new message). | PD (V1) |
| BR-14 | Emails are lowercased with a locale-independent mapping (`Locale.ROOT`) before every save and every lookup. They are unique, not blank, and at most 320 characters. | PD (ADR-005, V1) |
| BR-15 | Concurrent ticket updates use optimistic locking on `tickets.version`. A conflicting update fails with 409 and changes nothing. | PD (ADR-006) |
| BR-16 | Creating a ticket and its first message is one transaction. Either both exist or neither does. | EI, PD |
| BR-17 | All messages, from either role and through any entry point, are written by one operation that applies BR-04..BR-06, BR-09, BR-11 and BR-12. | PD (agreed in review) |
| BR-18 | `referenceNumber`, when present, is stripped of surrounding whitespace, matches 1–50 ASCII letters, digits or hyphens, and is stored uppercase. | PD (agreed in review) |

---

## 5. Data model

Source of truth: Flyway `V1__initial_schema.sql` (frozen). All primary keys are identity
`bigint` columns. Enum-like columns are `varchar` with a `CHECK` (ADR-004).

### users

| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK, identity |
| email | varchar(320) | NOT NULL, UNIQUE, lowercase CHECK, not-blank CHECK |
| password_hash | varchar(255) | NOT NULL |
| name | varchar(100) | NOT NULL |
| role | varchar(20) | NOT NULL, `CUSTOMER` or `AGENT` |
| created_at | timestamptz | NOT NULL, default now() |

### tickets

| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK, identity |
| customer_id | bigint | NOT NULL, FK → users |
| assignee_id | bigint | nullable, FK → users |
| subject | varchar(200) | NOT NULL, not-blank CHECK |
| category | varchar(20) | NOT NULL, `GENERAL`, `POLICY` or `CLAIM` |
| reference_number | varchar(50) | nullable |
| status | varchar(20) | NOT NULL, default `OPEN`, one of the four statuses |
| created_at | timestamptz | NOT NULL, default now() |
| updated_at | timestamptz | NOT NULL, default now() |
| last_message_at | timestamptz | NOT NULL, default now() |
| version | bigint | NOT NULL, default 0 (optimistic locking) |

Indexes: `(customer_id, last_message_at DESC)`, `(assignee_id)`, `(status, last_message_at DESC)`.

### messages

| Column | Type | Constraints |
|---|---|---|
| id | bigint | PK, identity |
| ticket_id | bigint | NOT NULL, FK → tickets, ON DELETE CASCADE |
| author_id | bigint | NOT NULL, FK → users |
| body | text | NOT NULL, not-blank CHECK |
| created_at | timestamptz | NOT NULL, default now() |

Indexes: `(ticket_id, created_at, id)`, `(author_id)`.

### Relationships
- A user with role CUSTOMER owns 0..n tickets (`customer_id`).
- A user with role AGENT is assigned 0..n tickets (`assignee_id`). The database does not check
  the role; the application enforces it (BR-10).
- A ticket has 1..n messages: at least the first one (BR-16).
- A user writes 0..n messages (`author_id`).

### Comparison with V1 schema

The data model of the earlier implementation was inferred from its behaviour; it had no
available schema definition.

**Matches (conceptually)**
- Ticket id, status, `created_at`, `updated_at`.
- Ticket assignee (an agent reference).
- Message → ticket link, message `created_at`.
- Message text (V1 `body`).
- User name and email; a password hash for staff accounts.

**In the earlier implementation, absent in V1**
- Customer key based on a national document number, and a phone number used to log in.
- External identity-provider id and a login-method flag (local or external).
- A user "active" flag (written but never checked).
- A separate staff table with its own login name and a free-text role.
- Ticket `closed_by` and `closed_at`.
- A boolean "sent by staff" flag on messages, instead of an author reference.
- Message delivery and read timestamps.
- A server-side session table.
- A three-value status set (new, open, closed).

**In V1, absent in the earlier implementation**
- A single users table with a `role` (CUSTOMER or AGENT), and email + password login for all users.
- Ticket `subject`, `category`, `reference_number`, `last_message_at` and `version`.
- Message `author_id` (which agent wrote a message was not recorded before).
- The four-value status set (OPEN, ANSWERED, CUSTOMER_REPLIED, CLOSED).
- Lowercase, unique and not-blank constraints on email, and not-blank constraints on subject and body.

### Candidates for future migrations (V2+)

These are listed only. V1 is not changed, and none of these is required by the MVP.
- Read tracking, for example a per-user last-read marker, for post-MVP unread counts.
- `closed_at` and `closed_by` on tickets, for reporting.
- Customer phone number.
- External identity link, if social login is added.
- Database-level CHECKs mirroring application rules: body length (BR-11), reference-number
  format (BR-18), and assignee role (BR-10, which would need a trigger or a schema change).

FR-08, FR-11 and FR-12 need no schema change. The FR-08 summary is an aggregate over
`tickets` grouped by `customer_id`, supported by the existing `(customer_id, last_message_at DESC)` index.

---

## 6. UI flows

Both zones live in one application (ADR-011). Source: EI for the overall shape of the journeys,
PD for the fields and actions.

### Customer zone
1. **Login**: email and password (FR-01).
2. **My tickets**: a list with a status filter and the newest activity first (FR-04), plus a
   "New ticket" action.
3. **New ticket**: subject, category, optional reference number and first message. On success it
   opens the new ticket's thread (FR-03).
4. **Ticket thread**: the messages in order, with customer and agent messages visually distinct,
   and a composer (FR-05, FR-06). On a CLOSED ticket the composer stays available and tells the
   user that sending a message reopens the ticket.
5. **Logout** (FR-02).

### Agent zone
1. **Login**: email and password (FR-01).
2. **Ticket queue**: a table with status and assignee filters, including "unassigned" (FR-07).
3. **By customer**: a list of customers with ticket counts and latest activity (FR-08).
   Selecting a customer opens that customer's tickets with the queue's status and assignee
   filters (FR-07 with the customer filter).
4. **Ticket detail**: customer info, the thread, and a reply box that is disabled while the
   ticket is CLOSED (FR-09, FR-10). It has Close and Reopen actions (FR-11) and an assignee
   selector (FR-12).
5. **Logout** (FR-02).

While a thread is open, it refreshes by polling (NFR-06).

---

## 7. Non-functional requirements

| ID | Requirement | Source |
|---|---|---|
| NFR-01 | Authentication uses a stateless JWT bearer access token signed by the application. Its lifetime is 60 minutes, and there is no refresh token. There is no server-side logout or revocation (known limitation, FR-02). | PD (ADR-009, agreed in review) |
| NFR-02 | Passwords are stored only as hashes. Passwords and tokens are never written to logs. | PD (agreed in review) |
| NFR-03 | Every error response uses one format: RFC 9457 Problem Details, with the status mapping in section 3 (400/401/403/404/409). | PD (ADR-008) |
| NFR-04 | All input validation is done on the server. Client-side checks only improve usability and are never relied on. | PD (agreed in review) |
| NFR-05 | Ticket lists are sorted by `last_message_at` descending, then `id` descending. Threads are sorted by `created_at` ascending, then `id` ascending. The customer summary (FR-08) is sorted by `lastMessageAt` descending, then customer `id` descending. | PD (V1 indexes, agreed in review) |
| NFR-06 | Chat updates use REST polling every 3–5 seconds, only while a thread is open. | PD (ADR-010) |
| NFR-07 | The API is documented with OpenAPI 3.1, generated by springdoc. The spec (`/v3/api-docs`) and Swagger UI (`/swagger-ui.html`) are public. Each operation lists every 4xx response it can return, with the Problem Details schema. | PD (ADR-008) |
| NFR-08 | Messages are written through a single write path (BR-17). No second channel bypasses validation or the status rules. | PD (agreed in review) |

---

## 8. Out of scope and open questions

### Out of scope for the MVP
- Login with an external identity provider (social login).
- Login with a document number and phone number.
- An administrator role, or roles other than CUSTOMER and AGENT.
- Self-registration. MVP accounts (agents and demo customers) are seeded.
- A password policy (follows from the point above).
- Refresh tokens and server-side token revocation.
- Real-time server push and typing indicators (ADR-010).
- Unread counts, read receipts and delivery receipts (post-MVP).
- Pagination of lists and threads (post-MVP).
- Search by ticket id, customer name or email (post-MVP).
- Unassigning a ticket.
- Offline mode and installable-app behaviour.
- Localisation and region-specific formatting rules.

### Open questions

| ID | Question | Status |
|---|---|---|
| OQ-01 | What exact shape should the "grouped by customer" agent view (FR-08) have? For example, a customer list with ticket counts that drills down to that customer's tickets, or a single list split into sections by customer? Are the FR-07 filters available in this view? | Resolved: a customer summary with ticket counts and latest activity, which drills down to FR-07 with a customer filter, so the FR-07 filters are available there. A single sectioned list was rejected: without pagination (out of scope) it returns every ticket of every customer in one response, while the summary grows only with the number of customers. See FR-08. |