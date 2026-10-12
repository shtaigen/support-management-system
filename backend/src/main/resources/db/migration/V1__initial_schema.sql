-- V1: core schema for the support management system.
-- Enum-like columns are varchar + CHECK (not native enums) so adding a value
-- is a simple constraint swap, not ALTER TYPE.

-- Emails are stored normalized to lowercase. The application MUST lowercase
-- an email before saving it and before looking it up; the CHECK rejects
-- anything that wasn't normalized. A plain UNIQUE on the stored value is
-- enough for case-insensitive uniqueness, and exact-match queries
-- (findByEmail) use its index.
CREATE TABLE users (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         varchar(320) NOT NULL,
    password_hash varchar(255) NOT NULL,
    name          varchar(100) NOT NULL,
    role          varchar(20)  NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT users_email_uk UNIQUE (email),
    CONSTRAINT users_email_lowercase_ck CHECK (email = lower(email)),
    CONSTRAINT users_email_not_blank_ck CHECK (btrim(email) <> ''),
    CONSTRAINT users_role_ck CHECK (role IN ('CUSTOMER', 'AGENT'))
);

CREATE TABLE tickets (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id      bigint       NOT NULL,
    assignee_id      bigint,
    subject          varchar(200) NOT NULL,
    category         varchar(20)  NOT NULL,
    reference_number varchar(50),
    status           varchar(20)  NOT NULL DEFAULT 'OPEN',
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    last_message_at  timestamptz  NOT NULL DEFAULT now(),
    version          bigint       NOT NULL DEFAULT 0,
    CONSTRAINT tickets_customer_id_fk FOREIGN KEY (customer_id) REFERENCES users (id),
    CONSTRAINT tickets_assignee_id_fk FOREIGN KEY (assignee_id) REFERENCES users (id),
    CONSTRAINT tickets_category_ck CHECK (category IN ('GENERAL', 'POLICY', 'CLAIM')),
    CONSTRAINT tickets_status_ck CHECK (status IN ('OPEN', 'ANSWERED', 'CUSTOMER_REPLIED', 'CLOSED')),
    CONSTRAINT tickets_subject_not_blank_ck CHECK (btrim(subject) <> '')
);

-- Customer's ticket list, newest activity first; also serves the customer_id FK.
CREATE INDEX tickets_customer_id_last_message_at_idx ON tickets (customer_id, last_message_at DESC);
-- Agent work queue; also serves the assignee_id FK.
CREATE INDEX tickets_assignee_id_idx ON tickets (assignee_id);
-- Agent inbox filtered by status, newest activity first.
CREATE INDEX tickets_status_last_message_at_idx ON tickets (status, last_message_at DESC);

CREATE TABLE messages (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id  bigint      NOT NULL,
    author_id  bigint      NOT NULL,
    body       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT messages_ticket_id_fk FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE,
    CONSTRAINT messages_author_id_fk FOREIGN KEY (author_id) REFERENCES users (id),
    CONSTRAINT messages_body_not_blank_ck CHECK (btrim(body) <> '')
);

-- Ticket thread in chronological order (id breaks ties); also serves the ticket_id FK.
CREATE INDEX messages_ticket_id_created_at_idx ON messages (ticket_id, created_at, id);
CREATE INDEX messages_author_id_idx ON messages (author_id);
