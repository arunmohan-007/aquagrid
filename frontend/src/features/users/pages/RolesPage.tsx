import { Fragment, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  Chip,
  Collapse,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  IconButton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/AddOutlined';
import EditIcon from '@mui/icons-material/EditOutlined';
import DeleteIcon from '@mui/icons-material/DeleteOutlined';
import ExpandIcon from '@mui/icons-material/ExpandMoreOutlined';
import { useAuth } from '@/lib/auth/AuthProvider';
import { problemMessage } from '@/lib/api/problem';
import { useCreateRole, useDeleteRole, useRoles, useUpdateRole } from '../hooks/useUsers';

/**
 * Role catalogue.
 *
 * System roles are displayed read-only (they are product data shared by every tenant and
 * immutable through the API). Tenant-authored roles can be edited or deleted — deletion is
 * refused by the server while the role is assigned to any user, and that conflict is surfaced
 * verbatim.
 */
export default function RolesPage() {
  const { hasPermission } = useAuth();
  const { data: roles, isLoading, error } = useRoles();
  const createRole = useCreateRole();
  const updateRole = useUpdateRole();
  const deleteRole = useDeleteRole();

  const canManage = hasPermission('identity:role:manage');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [editing, setEditing] = useState<{ id?: string; code?: string; name: string; description?: string | undefined; permissions: string[] } | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const toggle = (code: string) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(code)) next.delete(code);
      else next.add(code);
      return next;
    });

  const handleSave = async (draft: { id?: string; code?: string; name: string; description?: string | undefined; permissions: string[] }) => {
    try {
      if (draft.id) {
        await updateRole.mutateAsync({
          roleId: draft.id,
          payload: { name: draft.name, description: draft.description, permissions: draft.permissions },
        });
      } else {
        await createRole.mutateAsync({
          code: draft.code!,
          name: draft.name,
          description: draft.description,
          permissions: draft.permissions.length ? draft.permissions : ['gis:map:view'],
        });
      }
      setEditing(null);
    } catch (err) {
      setMessage(problemMessage(err));
    }
  };

  const handleDelete = async (roleId: string) => {
    try {
      await deleteRole.mutateAsync(roleId);
    } catch (err) {
      setMessage(problemMessage(err));
    }
  };

  return (
    <Stack spacing={2.5}>
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={2}>
        <Box>
          <Typography variant="h1">Roles</Typography>
          <Typography variant="body2" color="text.secondary">
            System roles are shared and immutable. Custom roles belong to your organisation.
          </Typography>
        </Box>
        {canManage ? (
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            onClick={() => setEditing({ name: '', description: '', permissions: [] })}
          >
            New role
          </Button>
        ) : null}
      </Stack>

      {message ? (
        <Alert severity="error" variant="outlined" onClose={() => setMessage(null)}>
          {message}
        </Alert>
      ) : null}
      {error ? (
        <Alert severity="error" variant="outlined">
          Could not load roles. {problemMessage(error)}
        </Alert>
      ) : null}

      <Card variant="outlined">
        <TableContainer>
          <Table size="small" aria-label="Roles">
            <TableHead>
              <TableRow>
                <TableCell />
                <TableCell>Role</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Permissions</TableCell>
                {canManage ? <TableCell align="right">Actions</TableCell> : null}
              </TableRow>
            </TableHead>
            <TableBody>
              {isLoading ? (
                <TableRow>
                  <TableCell colSpan={canManage ? 5 : 4} align="center" sx={{ py: 4 }}>
                    <Typography variant="body2" color="text.secondary">
                      Loading…
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : (
                (roles ?? []).map((role) => (
                  <Fragment key={role.id}>
                    <TableRow hover>
                      <TableCell sx={{ width: 40 }}>
                        <IconButton size="small" onClick={() => toggle(role.code)} aria-label="Expand permissions">
                          <ExpandIcon
                            fontSize="small"
                            sx={{ transform: expanded.has(role.code) ? 'rotate(180deg)' : 'none', transition: 'transform 0.15s' }}
                          />
                        </IconButton>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2" fontWeight={600}>
                          {role.name}
                        </Typography>
                        <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
                          {role.code}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        {role.system ? (
                          <Chip size="small" label="System" variant="outlined" />
                        ) : (
                          <Chip size="small" label="Custom" color="primary" />
                        )}
                      </TableCell>
                      <TableCell>
                        <Typography variant="caption" color="text.secondary">
                          {role.permissionCount}
                        </Typography>
                      </TableCell>
                      {canManage ? (
                        <TableCell align="right">
                          {role.system ? null : (
                            <Stack direction="row" spacing={0.5} justifyContent="flex-end">
                              <IconButton size="small" aria-label="Edit role" onClick={() => setEditing({ id: role.id, code: role.code, name: role.name, description: role.description, permissions: [] })}>
                                <EditIcon fontSize="small" />
                              </IconButton>
                              <IconButton size="small" aria-label="Delete role" color="error" onClick={() => handleDelete(role.id)}>
                                <DeleteIcon fontSize="small" />
                              </IconButton>
                            </Stack>
                          )}
                        </TableCell>
                      ) : null}
                    </TableRow>
                    <TableRow>
                      <TableCell colSpan={canManage ? 5 : 4} sx={{ py: 0, border: 0 }}>
                        <Collapse in={expanded.has(role.code)} unmountOnExit>
                          <Box sx={{ py: 2 }}>
                            {role.description ? (
                              <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
                                {role.description}
                              </Typography>
                            ) : null}
                            <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>
                              {/* permission list comes from a separate detail fetch; for now show count */}
                              <Typography variant="caption" color="text.secondary">
                                {role.permissionCount} permission(s) granted.
                              </Typography>
                            </Stack>
                          </Box>
                        </Collapse>
                      </TableCell>
                    </TableRow>
                  </Fragment>
                ))
              )}
            </TableBody>
          </Table>
        </TableContainer>
      </Card>

      <RoleEditorDialog
        editing={editing}
        onClose={() => setEditing(null)}
        onSave={handleSave}
        pending={createRole.isPending || updateRole.isPending}
      />
    </Stack>
  );
}

function RoleEditorDialog({
  editing,
  onClose,
  onSave,
  pending,
}: {
  editing: { id?: string; code?: string; name: string; description?: string | undefined; permissions: string[] } | null;
  onClose: () => void;
  onSave: (next: { id?: string; code?: string; name: string; description?: string | undefined; permissions: string[] }) => void;
  pending: boolean;
}) {
  // Local state seeded from the prop when the dialog opens. The parent holds a draft, but the
  // inputs here are controlled by this component so keystrokes actually re-render.
  const [draft, setDraft] = useState(editing);

  // Re-seed whenever a different role is opened (create vs edit of role A vs role B).
  const editKey = editing?.id ?? editing?.code ?? 'new';
  const [lastKey, setLastKey] = useState(editKey);
  if (editing && editKey !== lastKey) {
    setDraft(editing);
    setLastKey(editKey);
  }

  if (!editing || !draft) return null;
  const isCreate = !editing.id;

  const update = (patch: Partial<typeof draft>) => setDraft((prev) => (prev ? { ...prev, ...patch } : prev));

  return (
    <Dialog open={Boolean(editing)} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{isCreate ? 'Create custom role' : `Edit ${editing.code}`}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {isCreate ? (
            <TextField
              label="Role code"
              value={draft.code ?? ''}
              onChange={(e) => update({ code: e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, '') })}
              helperText="Uppercase letters, digits and underscores, e.g. ZONE_SUPERVISOR"
              required
              fullWidth
            />
          ) : null}
          <TextField
            label="Display name"
            value={draft.name}
            onChange={(e) => update({ name: e.target.value })}
            required
            fullWidth
          />
          <TextField
            label="Description"
            value={draft.description ?? ''}
            onChange={(e) => update({ description: e.target.value })}
            multiline
            minRows={2}
            fullWidth
          />
          <Alert severity="info" variant="outlined">
            Permission selection is refined in a later iteration. For now the API accepts a permission code list.
          </Alert>
        </Stack>
      </DialogContent>
      <Divider />
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={pending || !draft.name || (isCreate && !draft.code)}
          onClick={() => onSave(draft)}
        >
          {pending ? 'Saving…' : isCreate ? 'Create' : 'Save'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
