import { Link as RouterLink } from 'react-router-dom';
import { Box, Button, Stack, Typography } from '@mui/material';
import BlockIcon from '@mui/icons-material/BlockOutlined';

export default function ForbiddenPage() {
  return (
    <Box className="flex min-h-screen items-center justify-center px-6">
      <Stack spacing={2} alignItems="center" sx={{ maxWidth: 420, textAlign: 'center' }}>
        <BlockIcon sx={{ fontSize: 56 }} color="warning" />
        <Typography variant="h1">You do not have access to this area</Typography>
        {/* Names the remedy. "Access denied" alone generates a support ticket. */}
        <Typography variant="body2" color="text.secondary">
          Your account does not hold the permission this page requires. Your organisation
          administrator can grant it.
        </Typography>
        <Button component={RouterLink} to="/home" variant="contained">
          Back to all modules
        </Button>
      </Stack>
    </Box>
  );
}
