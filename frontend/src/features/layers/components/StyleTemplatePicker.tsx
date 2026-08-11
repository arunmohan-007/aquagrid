import { Box, Card, Chip, Stack, Typography } from '@mui/material';
import { useStyleTemplates } from '../hooks/useLayers';
import type { StyleField, StyleTemplate } from '../types';

/**
 * Starting points for a new style.
 *
 * A blank editor asks for a fill colour, a stroke colour, a width, an opacity and a zoom range before
 * it shows anything, for a decision usually described as "like the mains, but red". These are that
 * sentence, and they come from the server so a template can only ever produce a style the server
 * would accept.
 *
 * Applying one is not a mode — it fills the form and gets out of the way. Everything stays editable,
 * which is why the swatches are a starting point rather than a preset the style is then locked into.
 */
export function StyleTemplatePicker({
  layerId,
  fields,
  onApply,
}: {
  layerId: string;
  fields: StyleField[];
  onApply: (template: StyleTemplate, classifyField: string, labelField: string) => void;
}) {
  const { data: templates } = useStyleTemplates(layerId || undefined);

  if (!templates || templates.length === 0) return null;

  return (
    <Box>
      <Typography variant="caption" sx={{ opacity: 0.75, display: 'block', mb: 1 }}>
        Start from a template, then adjust anything. Every value stays editable.
      </Typography>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))' },
          gap: 1,
        }}
      >
        {templates.map((template) => (
          <Card
            key={template.id}
            variant="outlined"
            onClick={() =>
              onApply(
                template,
                resolveField(template.suggestedField, fields),
                resolveField(template.labelField, fields),
              )
            }
            sx={{
              p: 1.25,
              cursor: 'pointer',
              transition: 'border-color 120ms, background-color 120ms',
              '&:hover': { borderColor: 'primary.main', bgcolor: 'action.hover' },
            }}
          >
            <Stack direction="row" spacing={1.25} alignItems="flex-start">
              <TemplateSwatch template={template} />
              <Box sx={{ minWidth: 0 }}>
                <Stack direction="row" spacing={0.75} alignItems="center">
                  <Typography variant="body2" sx={{ fontWeight: 700 }}>
                    {template.name}
                  </Typography>
                  {template.ruleSeeds.length > 0 ? (
                    <Chip
                      size="small"
                      variant="outlined"
                      label={`${template.ruleSeeds.length} classes`}
                      sx={{ height: 18, fontSize: 10.5 }}
                    />
                  ) : null}
                </Stack>
                <Typography variant="caption" sx={{ opacity: 0.7, display: 'block', mt: 0.25 }}>
                  {template.description}
                </Typography>
              </Box>
            </Stack>
          </Card>
        ))}
      </Box>
    </Box>
  );
}

/**
 * Resolves a template's suggested field against the layer's own catalogue.
 *
 * The suggestion is used **only if Data Management actually has that field**. The templates suggest
 * `status` and `name` because most layers have them, but a layer surveyed by a different contractor
 * may call its condition `asset_state` and have no `status` at all — and a brand-new layer has no
 * fields whatsoever until someone adds them. Filling in a field that does not exist would produce a
 * style the server rejects on save, with an error the administrator did not cause. An empty string
 * leaves the picker asking, which is the honest state.
 */
function resolveField(suggested: string | null, fields: StyleField[]): string {
  if (!suggested) return '';
  return fields.some((f) => f.fieldName === suggested) ? suggested : '';
}

/** A miniature of what the template paints, drawn from its own symbol rather than from a guess. */
function TemplateSwatch({ template }: { template: StyleTemplate }) {
  /*
   * Keyed by the class label rather than by position, which is both stable and unique — the seeds are
   * the template's own named classes, so two swatches can share a colour but never a label.
   */
  const classes: { key: string; colour: string }[] = template.ruleSeeds.length > 0
    ? template.ruleSeeds.map((seed) => ({
        key: seed.label,
        colour: seed.symbol.fillColor ?? '#B9C2D0',
      }))
    : [{
        key: template.id,
        colour: template.symbol.fillColor ?? template.symbol.lineColor ?? '#B9C2D0',
      }];
  const line = template.families.includes('LINE') && !template.families.includes('POINT');
  const fill = template.families.includes('POLYGON') && !template.families.includes('POINT');

  return (
    <Stack
      direction={classes.length > 1 ? 'row' : 'column'}
      spacing={0.4}
      sx={{ pt: 0.4, flexShrink: 0, width: 34, flexWrap: 'wrap' }}
    >
      {classes.slice(0, 4).map((entry) => (
        <Box
          key={entry.key}
          sx={{
            width: classes.length > 1 ? 7 : line ? 30 : 14,
            height: line ? 3 : classes.length > 1 ? 7 : 14,
            borderRadius: line ? 1 : fill ? 0.5 : '50%',
            bgcolor: entry.colour,
            opacity: fill ? 0.55 : 1,
            border: fill ? `1.5px solid ${entry.colour}` : 'none',
          }}
        />
      ))}
    </Stack>
  );
}
