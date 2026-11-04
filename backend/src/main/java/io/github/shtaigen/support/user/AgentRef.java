package io.github.shtaigen.support.user;

/**
 * An agent as other agents see it (FR-13): no email, password hash or ticket data. Built by a
 * JPQL constructor expression in {@link UserRepository}, so only these columns are read.
 */
record AgentRef(long id, String name) {
}
