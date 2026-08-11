import { http } from '@/lib/api/httpClient';
import { toProblem, type ProblemDetail } from '@/lib/api/problem';
import type { ReportRequest } from '../types';

/**
 * One binary endpoint: `GET /reports/readings`, streamed rather than loaded into a JSON envelope.
 *
 * A blob response needs handling ordinary `apiGet` does not provide, for one specific reason: when
 * the server rejects the request — an inverted date window, for instance — the error body is still
 * JSON, but axios has been told to expect a blob and delivers the failure as one too. `toProblem`
 * reads `error.response.data.code`, which does not exist on a `Blob`; without re-parsing it here,
 * every rejection from this endpoint would render as a generic "connection problem" instead of the
 * server's actual reason. That would be the wrong lesson for the one error a user of this screen is
 * most likely to hit.
 */
export const reportsApi = {
  // `filename: string | undefined` spelled out, not `filename?:` — the project runs
  // exactOptionalPropertyTypes, which distinguishes an omitted property from one holding
  // undefined, and the server not sending Content-Disposition is legitimately the latter.
  async downloadReadings(
    request: ReportRequest,
  ): Promise<{ blob: Blob; filename: string | undefined }> {
    try {
      const response = await http.get('/reports/readings', {
        params: {
          format: request.format,
          deviceId: request.deviceId || undefined,
          deviceType: request.deviceType || undefined,
          transport: request.transport || undefined,
          metric: request.metric || undefined,
          from: request.from,
          to: request.to,
        },
        responseType: 'blob',
      });
      return { blob: response.data as Blob, filename: filenameFrom(response.headers) };
    } catch (error) {
      throw await problemFromBlobError(error);
    }
  },
};

/** RFC 6266 `attachment; filename="…"` — the name the server chose for the download. */
function filenameFrom(headers: Record<string, unknown>): string | undefined {
  const disposition = headers['content-disposition'];
  if (typeof disposition !== 'string') return undefined;
  const match = /filename="?([^";]+)"?/i.exec(disposition);
  return match?.[1];
}

/**
 * Recovers the RFC 7807 body axios delivered as a `Blob` because the request was configured for
 * one, and re-throws something `problemMessage` can actually read.
 */
async function problemFromBlobError(error: unknown): Promise<Error & { problem?: ProblemDetail }> {
  const data = (error as { response?: { data?: unknown } })?.response?.data;
  if (data instanceof Blob && data.type.includes('json')) {
    try {
      const parsed = JSON.parse(await data.text()) as ProblemDetail;
      const wrapped = new Error(parsed.detail ?? parsed.title ?? 'Export failed') as Error & {
        problem?: ProblemDetail;
      };
      wrapped.problem = parsed;
      return wrapped;
    } catch {
      // Fall through — the blob was not the JSON body we hoped for.
    }
  }
  const problem = toProblem(error);
  return Object.assign(new Error(problem.detail ?? problem.title ?? 'Export failed'), { problem });
}
