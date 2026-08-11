import type { ReactNode } from 'react';
import { Box, Card, Skeleton, Stack, Typography } from '@mui/material';

/**
 * A headline number.
 *
 * A single current value is a stat tile, not a one-bar chart — the number *is* the visualisation,
 * and wrapping it in axes would add ink that carries nothing. `hero` doubles the type size for the
 * one figure the page leads with; the rest stay quiet so the lead reads first.
 *
 * The accent is spent on a hairline and a soft icon wash rather than on the numerals: value text
 * wears a text token so it stays legible, and identity comes from the coloured mark beside it.
 */
export function StatTile({
  label,
  value,
  unit,
  caption,
  accent,
  icon,
  hero,
  loading,
}: {
  label: string;
  value: string;
  unit?: string;
  caption?: string;
  /** Hue for the hairline, icon wash and glow. */
  accent: string;
  icon: ReactNode;
  hero?: boolean;
  loading?: boolean;
}) {
  return (
    <Card
      variant="outlined"
      sx={{
        position: 'relative',
        height: '100%',
        px: 2.25,
        py: 2,
        overflow: 'hidden',
        borderColor: 'divider',
        transition: 'border-color 180ms ease, transform 180ms ease',
        '&:hover': { borderColor: `${accent}66`, transform: 'translateY(-2px)' },
        // Lit top edge in the tile's own hue — the only place the accent touches the card.
        '&::before': {
          content: '""',
          position: 'absolute',
          insetInline: 0,
          top: 0,
          height: '2px',
          background: `linear-gradient(90deg, transparent, ${accent}, transparent)`,
        },
      }}
    >
      <Stack direction="row" alignItems="flex-start" spacing={1.5}>
        <Box
          aria-hidden
          sx={{
            display: 'grid',
            placeItems: 'center',
            flexShrink: 0,
            width: hero ? 44 : 38,
            height: hero ? 44 : 38,
            borderRadius: 2.5,
            color: accent,
            bgcolor: `${accent}1F`,
            border: `1px solid ${accent}33`,
            '& .MuiSvgIcon-root': { fontSize: hero ? 24 : 20 },
          }}
        >
          {icon}
        </Box>

        <Box sx={{ minWidth: 0, flexGrow: 1 }}>
          <Typography
            sx={{
              fontSize: 11,
              fontWeight: 700,
              letterSpacing: '0.07em',
              textTransform: 'uppercase',
              color: 'text.secondary',
            }}
          >
            {label}
          </Typography>

          {loading ? (
            <Skeleton
              variant="text"
              width={hero ? 150 : 90}
              sx={{ fontSize: hero ? '2.6rem' : '1.7rem', bgcolor: 'rgba(255,255,255,0.06)' }}
            />
          ) : (
            <Stack direction="row" alignItems="baseline" spacing={0.75} sx={{ mt: 0.25 }}>
              <Typography
                sx={{
                  fontSize: hero ? '2.5rem' : '1.7rem',
                  fontWeight: 700,
                  lineHeight: 1.1,
                  letterSpacing: '-0.02em',
                  color: 'text.primary',
                  fontVariantNumeric: 'tabular-nums',
                }}
              >
                {value}
              </Typography>
              {unit ? (
                <Typography sx={{ fontSize: hero ? 16 : 13, fontWeight: 600, color: 'text.secondary' }}>
                  {unit}
                </Typography>
              ) : null}
            </Stack>
          )}

          {caption ? (
            <Typography sx={{ mt: 0.5, fontSize: 12, color: 'text.secondary' }}>{caption}</Typography>
          ) : null}
        </Box>
      </Stack>
    </Card>
  );
}
