import { Link as RouterLink } from 'react-router-dom';
import { Box, Button, Stack, Typography } from '@mui/material';
import ExploreOffIcon from '@mui/icons-material/ExploreOffOutlined';

export default function NotFoundPage() {
  return (
    <Box className="flex min-h-screen items-center justify-center px-6">
      <Stack spacing={2} alignItems="center" sx={{ maxWidth: 420, textAlign: 'center' }}>
        <ExploreOffIcon sx={{ fontSize: 56 }} color="disabled" />
        <Typography variant="h1">Page not found</Typography>
        <Typography variant="body2" color="text.secondary">
          The page you are looking for does not exist, or belongs to a module that is not yet
          enabled for your organisation.
        </Typography>
        <Button component={RouterLink} to="/" variant="contained">
          Go to AquaGrid
        </Button>
      </Stack>
    </Box>
  );
}
