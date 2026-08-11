import { AxiosError } from 'axios';

/**
 * The RFC 7807 error document every AquaGrid endpoint returns.
 *
 * The client branches on `code`, never on `detail`. `detail` is human-facing prose that
 * may be reworded or localised at any time; `code` is part of the API contract.
 */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  code: string;
  timestamp?: string;
  traceId?: string;
  errors?: Array<{ field: string; message: string }>;
  /** Populated on 423 and 429 so the UI can render a real countdown. */
  retryAfterSeconds?: number;
  lockedUntil?: string;
  violations?: string[];
}

export type ApiErrorCode =
  | 'AUTH_INVALID_CREDENTIALS'
  | 'AUTH_ACCOUNT_LOCKED'
  | 'AUTH_ACCOUNT_DISABLED'
  | 'AUTH_ACCOUNT_PENDING'
  | 'AUTH_TOKEN_INVALID'
  | 'AUTH_TOKEN_EXPIRED'
  | 'AUTH_REQUIRED'
  | 'AUTH_REFRESH_TOKEN_MISSING'
  | 'AUTH_REFRESH_TOKEN_REUSED'
  | 'MFA_CODE_INVALID'
  | 'MFA_REQUIRED'
  | 'PASSWORD_POLICY_VIOLATION'
  | 'PASSWORD_REUSED'
  | 'PASSWORD_RESET_TOKEN_INVALID'
  | 'RATE_LIMIT_EXCEEDED'
  | 'TENANT_INACTIVE'
  | 'VALIDATION_FAILED'
  | 'OPERATION_NOT_PERMITTED'
  | 'INTERNAL_ERROR'
  | (string & {});

export function isProblemDetail(value: unknown): value is ProblemDetail {
  return (
    typeof value === 'object' &&
    value !== null &&
    'code' in value &&
    typeof (value as ProblemDetail).code === 'string'
  );
}

export function toProblem(error: unknown): ProblemDetail {
  if (error instanceof AxiosError) {
    if (isProblemDetail(error.response?.data)) {
      return error.response.data;
    }
    /*
     * No problem document means the request never reached the application: DNS failure,
     * TLS failure, the proxy is down, or the browser is offline. Saying "an error
     * occurred" here sends the user to support; saying it is a connectivity problem lets
     * them check their network first.
     */
    return {
      status: error.response?.status ?? 0,
      code: error.code === 'ECONNABORTED' ? 'REQUEST_TIMEOUT' : 'NETWORK_ERROR',
      title: 'Connection problem',
      detail:
        error.code === 'ECONNABORTED'
          ? 'The server took too long to respond. Please try again.'
          : 'Could not reach the AquaGrid server. Check your connection and try again.',
    };
  }
  return {
    status: 0,
    code: 'UNEXPECTED_ERROR',
    title: 'Unexpected error',
    detail: error instanceof Error ? error.message : 'An unexpected error occurred.',
  };
}

/** The message to put in front of the user, preferring the server's own wording. */
export function problemMessage(error: unknown, fallback = 'Something went wrong.'): string {
  const problem = toProblem(error);
  if (problem.violations?.length) {
    return problem.violations.join(' ');
  }
  if (problem.errors?.length) {
    return problem.errors.map((entry) => entry.message).join(' ');
  }
  return problem.detail ?? problem.title ?? fallback;
}
