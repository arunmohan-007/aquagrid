import {
  Autocomplete,
  Box,
  Button,
  Card,
  Chip,
  IconButton,
  MenuItem,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/AddOutlined';
import DeleteIcon from '@mui/icons-material/DeleteOutlineOutlined';
import UpIcon from '@mui/icons-material/ArrowUpwardOutlined';
import DownIcon from '@mui/icons-material/ArrowDownwardOutlined';
import AutoIcon from '@mui/icons-material/AutoFixHighOutlined';
import { useFieldValues } from '../hooks/useLayers';
import { SymbolEditor } from './SymbolEditor';
import type {
  GeometryFamily,
  StyleField,
  StyleOperatorCode,
  StyleRule,
  StyleTypeCode,
  StyleVocabulary,
} from '../types';

/**
 * A spread of distinct hues for generated classes.
 *
 * Used only to seed a generated set — every colour stays editable, and nothing here is applied to a
 * class the administrator created themselves. Saturated red, amber and orange are absent on purpose:
 * the platform reserves them for alarm severity, and a map that spends them on "PVC" leaves an
 * operator unable to read urgency from colour alone, which is the one thing colour must carry.
 */
const CLASS_PALETTE = [
  '#3B82F6',
  '#06B6D4',
  '#14B8A6',
  '#8B5CF6',
  '#EC4899',
  '#0EA5E9',
  '#6366F1',
  '#A855F7',
  '#0891B2',
  '#C026D3',
];

interface Props {
  layerId: string;
  styleType: StyleTypeCode;
  classifyField: string;
  onClassifyField: (field: string) => void;
  fields: StyleField[];
  rules: StyleRule[];
  onRules: (rules: StyleRule[]) => void;
  family: GeometryFamily;
  vocabulary: StyleVocabulary | undefined;
}

/**
 * The rule builder: field, operator, value, style.
 *
 * Every field offered comes from `fields`, which is Data Management's catalogue for the layer. There
 * is no second attribute list in this module, and the server refuses a rule naming a field the
 * catalogue does not have — so the picker and the validation cannot disagree.
 *
 * Which operators are offered depends on the field's declared type, from the same catalogue.
 * Comparing a text field with `>` is not an error the renderer would report; it is a rule that
 * silently never matches, which is the hardest kind of styling bug to see. Hiding those operators is
 * the client half of a check the server also makes.
 */
export function StyleRuleBuilder({
  layerId,
  styleType,
  classifyField,
  onClassifyField,
  fields,
  rules,
  onRules,
  family,
  vocabulary,
}: Props) {
  const perRuleField = styleType === 'RULE_BASED';
  const field = fields.find((f) => f.fieldName === classifyField);
  const { data: values } = useFieldValues(layerId, perRuleField ? undefined : classifyField);

  const setRule = (index: number, next: Partial<StyleRule>) =>
    onRules(rules.map((rule, i) => (i === index ? { ...rule, ...next } : rule)));

  const move = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= rules.length) return;
    const next = [...rules];
    const [moved] = next.splice(index, 1);
    next.splice(target, 0, moved!);
    // Re-numbered on every move because sort order is the evaluation order — MapLibre's `case` is
    // first-match, so a stale number here means the map resolves overlapping rules differently from
    // the list the administrator is looking at.
    onRules(next.map((rule, i) => ({ ...rule, sortOrder: (i + 1) * 10 })));
  };

  const addRule = () =>
    onRules([
      ...rules,
      {
        /*
         * A client-side id, so React can key the list stably.
         *
         * Keying by array index breaks editing outright: reordering a rule would move its text-field
         * state to whichever rule took its place, so an operator dragging a band up would watch its
         * value follow the position rather than the band. Rules loaded from the server already carry
         * an id; this gives the unsaved ones one too, and `toRequest` strips it before saving.
         */
        id: crypto.randomUUID(),
        fieldName: perRuleField ? (fields[0]?.fieldName ?? '') : classifyField,
        operator: styleType === 'GRADUATED' ? 'BETWEEN' : 'EQ',
        value1: '',
        value2: '',
        valueList: [],
        label: '',
        symbol: { fillColor: CLASS_PALETTE[rules.length % CLASS_PALETTE.length]! },
        sortOrder: (rules.length + 1) * 10,
      },
    ]);

  /**
   * Builds a class per distinct value, from what the layer's data actually holds.
   *
   * The alternative is typing them from memory, which is how a category ends up spelled `FAULTY` in
   * the style and `FAULT` in the rows — a class that matches nothing and looks like a broken
   * renderer rather than a typo.
   */
  const generateCategories = () => {
    const distinct = (values?.values ?? []).slice(0, CLASS_PALETTE.length * 2);
    onRules(
      distinct.map((value, index) => ({
        id: crypto.randomUUID(),
        fieldName: classifyField,
        operator: 'EQ' as StyleOperatorCode,
        value1: value,
        label: value,
        symbol: { fillColor: CLASS_PALETTE[index % CLASS_PALETTE.length]! },
        sortOrder: (index + 1) * 10,
      })),
    );
  };

  /**
   * Builds evenly spaced bands across the observed range.
   *
   * Bands, not thresholds: a `BETWEEN` says what it includes, and the composer turns an ascending
   * set of them into a MapLibre `step`. The bounds come from the data rather than from 0–100,
   * because a water level that never exceeds 40 classified into four bands of 25 puts every reading
   * in the first two.
   */
  const generateBands = (count: number) => {
    const min = values?.minimum;
    const max = values?.maximum;
    if (min === null || min === undefined || max === null || max === undefined || max <= min) return;
    const width = (max - min) / count;
    onRules(
      Array.from({ length: count }, (_, index) => {
        const low = min + width * index;
        const high = index === count - 1 ? max : min + width * (index + 1);
        return {
          id: crypto.randomUUID(),
          fieldName: classifyField,
          operator: 'BETWEEN' as StyleOperatorCode,
          value1: round(low),
          value2: round(high),
          label: `${round(low)} – ${round(high)}`,
          symbol: { fillColor: CLASS_PALETTE[index % CLASS_PALETTE.length]! },
          sortOrder: (index + 1) * 10,
        };
      }),
    );
  };

  return (
    <Stack spacing={2}>
      {!perRuleField ? (
        <Stack direction="row" spacing={1.5} alignItems="flex-start">
          <TextField
            select
            size="small"
            label="Classify on"
            fullWidth
            value={classifyField}
            onChange={(e) => onClassifyField(e.target.value)}
            helperText="From Data Management’s catalogue for this layer. Add a field there and it appears here."
          >
            {fields
              .filter((f) => styleType !== 'GRADUATED' || f.numeric)
              .map((f) => (
                <MenuItem key={f.fieldName} value={f.fieldName}>
                  {f.displayName}
                  <Typography component="span" variant="caption" sx={{ opacity: 0.6, ml: 1 }}>
                    {f.fieldName} · {f.dataType}
                  </Typography>
                </MenuItem>
              ))}
          </TextField>
          <Tooltip
            title={
              styleType === 'GRADUATED'
                ? 'Create four bands across the values this layer actually holds'
                : 'Create a class for each value this layer actually holds'
            }
          >
            <span>
              <Button
                variant="outlined"
                size="small"
                startIcon={<AutoIcon />}
                disabled={!classifyField}
                onClick={() => (styleType === 'GRADUATED' ? generateBands(4) : generateCategories())}
                sx={{ mt: 0.25, whiteSpace: 'nowrap' }}
              >
                From data
              </Button>
            </span>
          </Tooltip>
        </Stack>
      ) : null}

      {!perRuleField && values ? (
        <Typography variant="caption" sx={{ opacity: 0.7 }}>
          {styleType === 'GRADUATED'
            ? values.minimum === null
              ? 'No numeric values found in this field yet.'
              : `Observed range: ${values.minimum} to ${values.maximum}.`
            : `${values.values.length} distinct value${values.values.length === 1 ? '' : 's'} in the data.`}
        </Typography>
      ) : null}

      <Stack spacing={1.5}>
        {rules.map((rule, index) => (
          <RuleCard
            key={rule.id ?? `${rule.fieldName}-${rule.sortOrder}`}
            index={index}
            total={rules.length}
            rule={rule}
            fields={fields}
            perRuleField={perRuleField}
            styleType={styleType}
            family={family}
            vocabulary={vocabulary}
            suggestions={field?.numeric ? [] : (values?.values ?? [])}
            onChange={(next) => setRule(index, next)}
            onRemove={() => onRules(rules.filter((_, i) => i !== index))}
            onMove={(delta) => move(index, delta)}
          />
        ))}
      </Stack>

      <Box>
        <Button
          size="small"
          startIcon={<AddIcon />}
          onClick={addRule}
          disabled={!perRuleField && !classifyField}
        >
          Add {styleType === 'GRADUATED' ? 'band' : styleType === 'CATEGORICAL' ? 'category' : 'rule'}
        </Button>
      </Box>

      {rules.length > 1 ? (
        <Typography variant="caption" sx={{ opacity: 0.7 }}>
          Rules are evaluated top to bottom and the first match wins. Reorder with the arrows —
          overlapping rules resolve to whichever comes first, here and on the map alike.
        </Typography>
      ) : null}
    </Stack>
  );
}

function RuleCard({
  index,
  total,
  rule,
  fields,
  perRuleField,
  styleType,
  family,
  vocabulary,
  suggestions,
  onChange,
  onRemove,
  onMove,
}: {
  index: number;
  total: number;
  rule: StyleRule;
  fields: StyleField[];
  perRuleField: boolean;
  styleType: StyleTypeCode;
  family: GeometryFamily;
  vocabulary: StyleVocabulary | undefined;
  suggestions: string[];
  onChange: (next: Partial<StyleRule>) => void;
  onRemove: () => void;
  onMove: (delta: number) => void;
}) {
  const field = fields.find((f) => f.fieldName === rule.fieldName);
  const operators = (vocabulary?.operators ?? []).filter((op) => {
    // A magnitude comparison on a text field compares strings — '9' sorts after '10' — so those
    // operators are withheld rather than offered and then refused on save.
    if (op.ordered && field && !field.numeric && !field.dataType.startsWith('DATE')) return false;
    // Categorical styles compose into a MapLibre `match`, which compares for equality and nothing
    // else. Offering anything more here would silently degrade the style to the general form.
    if (styleType === 'CATEGORICAL' && !['EQ', 'IN'].includes(op.value)) return false;
    if (styleType === 'GRADUATED' && op.value !== 'BETWEEN') return false;
    return true;
  });
  const arity = operators.find((op) => op.value === rule.operator)?.arity ?? 'ONE';

  return (
    <Card variant="outlined" sx={{ p: 1.75 }}>
      <Stack spacing={1.5}>
        <Stack direction="row" spacing={1} alignItems="center">
          <Chip size="small" label={index + 1} sx={{ minWidth: 34 }} />
          {perRuleField ? (
            <TextField
              select
              size="small"
              label="Field"
              sx={{ minWidth: 180 }}
              value={rule.fieldName}
              onChange={(e) => onChange({ fieldName: e.target.value })}
            >
              {fields.map((f) => (
                <MenuItem key={f.fieldName} value={f.fieldName}>
                  {f.displayName}
                </MenuItem>
              ))}
            </TextField>
          ) : null}
          <TextField
            select
            size="small"
            label="Operator"
            sx={{ minWidth: 130 }}
            value={rule.operator}
            onChange={(e) => onChange({ operator: e.target.value as StyleOperatorCode })}
          >
            {operators.map((op) => (
              <MenuItem key={op.value} value={op.value}>
                {op.symbol}
              </MenuItem>
            ))}
          </TextField>

          {arity === 'ONE' ? (
            <Autocomplete
              freeSolo
              size="small"
              sx={{ minWidth: 170 }}
              options={suggestions}
              value={rule.value1 ?? ''}
              onInputChange={(_, value) => onChange({ value1: value })}
              renderInput={(params) => <TextField {...params} label="Value" />}
            />
          ) : null}
          {arity === 'TWO' ? (
            <>
              <TextField
                size="small"
                label="From"
                sx={{ width: 110 }}
                value={rule.value1 ?? ''}
                onChange={(e) => onChange({ value1: e.target.value })}
              />
              <TextField
                size="small"
                label="To"
                sx={{ width: 110 }}
                value={rule.value2 ?? ''}
                onChange={(e) => onChange({ value2: e.target.value })}
              />
            </>
          ) : null}
          {arity === 'LIST' ? (
            <Autocomplete
              multiple
              freeSolo
              size="small"
              sx={{ minWidth: 240 }}
              options={suggestions}
              value={rule.valueList ?? []}
              onChange={(_, value) => onChange({ valueList: value as string[] })}
              renderInput={(params) => <TextField {...params} label="Values" />}
            />
          ) : null}

          <TextField
            size="small"
            label="Legend label"
            sx={{ minWidth: 150, flexGrow: 1 }}
            value={rule.label ?? ''}
            onChange={(e) => onChange({ label: e.target.value })}
            placeholder="Faulty"
          />

          <Stack direction="row">
            <IconButton size="small" disabled={index === 0} onClick={() => onMove(-1)}>
              <UpIcon fontSize="small" />
            </IconButton>
            <IconButton size="small" disabled={index === total - 1} onClick={() => onMove(1)}>
              <DownIcon fontSize="small" />
            </IconButton>
            <IconButton size="small" onClick={onRemove}>
              <DeleteIcon fontSize="small" />
            </IconButton>
          </Stack>
        </Stack>

        <Box sx={{ pl: 5.5 }}>
          <SymbolEditor
            value={rule.symbol}
            onChange={(symbol) => onChange({ symbol })}
            family={family}
            vocabulary={vocabulary}
            sparse
            compact
          />
          <Typography variant="caption" sx={{ opacity: 0.65 }}>
            Only what this class changes. Anything left alone follows the base symbol.
          </Typography>
        </Box>
      </Stack>
    </Card>
  );
}

function round(value: number): string {
  return String(Math.round(value * 100) / 100);
}
