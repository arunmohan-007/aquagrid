import { Box, Card, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { chartTokens, formatKm } from '../chartTheme';
import type { NetworkSummary, PanchayatSummary } from '../api/dashboardApi';

/**
 * The same figures as the charts, as numbers.
 *
 * Two jobs. It is the accessible fallback the colour method requires — every value the charts
 * encode in a hue is readable here without seeing colour at all — and it is the thing an engineer
 * actually copies into a report. A footer row carries the totals so the page's headline figures
 * can be checked against their own breakdown.
 */
export function PanchayatTable({ summary }: { summary: NetworkSummary }) {
  const rows = [...summary.panchayats].sort((a, b) => b.pipelineLengthM - a.pipelineLengthM);

  return (
    <Card variant="outlined" sx={{ p: 2.25 }}>
      <Typography variant="h4" sx={{ fontSize: '1.05rem', mb: 0.25 }}>
        Panchayat breakdown
      </Typography>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 1.5 }}>
        Every figure on this page, as numbers
      </Typography>

      <Box sx={{ overflowX: 'auto' }}>
        <Table size="small" sx={{ '& td, & th': { borderColor: 'divider' } }}>
          <TableHead>
            <TableRow>
              <HeadCell>Panchayat</HeadCell>
              <HeadCell align="right" swatch={chartTokens.pipeline}>Pipe network</HeadCell>
              <HeadCell align="right">Segments</HeadCell>
              <HeadCell align="right" swatch={chartTokens.facilities.tanks}>Over Head Tanks</HeadCell>
              <HeadCell align="right" swatch={chartTokens.facilities.boreWells}>Bore Wells</HeadCell>
              <HeadCell align="right" swatch={chartTokens.facilities.openWells}>Open Wells</HeadCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((row) => (
              <TableRow key={row.panchayat} hover>
                <TableCell sx={{ fontWeight: 600 }}>{row.panchayat}</TableCell>
                <NumberCell>{formatKm(row.pipelineLengthM)}</NumberCell>
                <NumberCell>{row.pipelineCount}</NumberCell>
                <NumberCell>{row.tanks}</NumberCell>
                <NumberCell>{row.boreWells}</NumberCell>
                <NumberCell>{row.openWells}</NumberCell>
              </TableRow>
            ))}

            <TableRow>
              <TableCell sx={{ fontWeight: 700, borderBottom: 'none' }}>Total</TableCell>
              <NumberCell total>{formatKm(summary.totalPipelineLengthM)}</NumberCell>
              <NumberCell total>{summary.pipelineCount}</NumberCell>
              <NumberCell total>{summary.tanks}</NumberCell>
              <NumberCell total>{summary.boreWells}</NumberCell>
              <NumberCell total>{summary.openWells}</NumberCell>
            </TableRow>
          </TableBody>
        </Table>
      </Box>
    </Card>
  );
}

/** Column heading, optionally carrying the swatch that identifies it in the charts above. */
function HeadCell({
  children,
  align,
  swatch,
}: {
  children: string;
  align?: 'right';
  swatch?: string;
}) {
  return (
    <TableCell align={align}>
      <Stack
        direction="row"
        alignItems="center"
        spacing={0.75}
        justifyContent={align === 'right' ? 'flex-end' : 'flex-start'}
      >
        {swatch ? (
          <Box aria-hidden sx={{ width: 8, height: 8, borderRadius: '2px', bgcolor: swatch, flexShrink: 0 }} />
        ) : null}
        <Typography
          component="span"
          sx={{
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: '0.05em',
            textTransform: 'uppercase',
            color: 'text.secondary',
            whiteSpace: 'nowrap',
          }}
        >
          {children}
        </Typography>
      </Stack>
    </TableCell>
  );
}

function NumberCell({ children, total }: { children: React.ReactNode; total?: boolean }) {
  return (
    <TableCell
      align="right"
      sx={{
        fontVariantNumeric: 'tabular-nums',
        fontWeight: total ? 700 : 500,
        color: total ? 'text.primary' : 'text.secondary',
        borderBottom: total ? 'none' : undefined,
      }}
    >
      {children}
    </TableCell>
  );
}

export type { PanchayatSummary };
