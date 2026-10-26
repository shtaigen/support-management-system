// Mirrors users_role_ck in backend V1__initial_schema.sql.
export type UserRole = 'CUSTOMER' | 'AGENT';

export const ROLE_HOME: Record<UserRole, string> = {
  CUSTOMER: '/client',
  AGENT: '/staff',
};

export function isUserRole(value: unknown): value is UserRole {
  return typeof value === 'string' && Object.hasOwn(ROLE_HOME, value);
}
