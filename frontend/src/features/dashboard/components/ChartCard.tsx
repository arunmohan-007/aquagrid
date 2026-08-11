import type { ReactNode } from 'react';
import { Box, Card, Skeleton, Stack, Typography } from '@mui/material';

/**
 * The frame every chart on the dashboard sits in.
 *
 * Title and subtitle live here rather than inside the plot, so the chart body stays pure data ink
 * and every figure on the page lines up its heading at the same height.
 */
export function ChartCard({
  title,
  subtitle,
  action,
  loading,
  empty,
  height = 300,
  children,
}: {
  title: string;
  subtitle?: string;
  action?: ReactNode;
  loading?: boolean;
  /** Rendered instead of the plot when there is nothing to draw. */
  empty?: string | undefined;
  height?: number;
  children: ReactNode;
}) {
  return (
    <Card variant="outlined" sx={{ height: '100%', p: 2.25 }}>
      <Stack direction="row" alignItems="flex-start" spacing={1.5} sx={{ mb: 1.5 }}>
        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          <Typography variant="h4" sx={{ fontSize: '1.05rem' }}>
            {title}
          </Typography>
          {subtitle ? (
            <Typography sx={{ mt: 0.25, fontSize: 12.5, color: 'text.secondary' }}>
              {subtitle}
            </Typography>
          ) : null}
        </Box>
        {action}
      </Stack>

      {loading ? (
        <Skeleton variant="rounded" height={height} sx={{ bgcolor: 'rgba(255,255,255,0.05)' }} />
      ) : empty ? (
        <Stack alignItems="center" justifyContent="center" sx={{ height }}>
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>{empty}</Typography>
        </Stack>
      ) : (
        children
      )}
    </Card>
  );
}
