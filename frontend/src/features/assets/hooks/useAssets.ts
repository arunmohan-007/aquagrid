import {
  useMutation,
  useQuery,
  useQueryClient,
  keepPreviousData,
} from '@tanstack/react-query';
import { assetsApi } from '../api/assetsApi';
import type { AssetListQuery, AssetRequest, AssetType } from '../types';

export const queryKeys = {
  assets: {
    all: ['assets'] as const,
    lists: () => [...queryKeys.assets.all, 'list'] as const,
    list: (query: AssetListQuery) => [...queryKeys.assets.lists(), query] as const,
    detail: (id: string) => [...queryKeys.assets.all, 'detail', id] as const,
    attachments: (id: string) => [...queryKeys.assets.all, 'attachments', id] as const,
  },
} as const;

const PAGE_SIZE = 25;

export function useAssetList(search: string, assetType: AssetType | undefined, page: number) {
  return useQuery({
    queryKey: queryKeys.assets.list({
      search: search.trim() || undefined,
      assetType: assetType || undefined,
      page,
      size: PAGE_SIZE,
    }),
    queryFn: () => assetsApi.list({
      search: search.trim() || undefined,
      assetType: assetType || undefined,
      page,
      size: PAGE_SIZE,
    }),
    placeholderData: keepPreviousData,
  });
}

export function useAsset(id: string | undefined) {
  return useQuery({
    queryKey: queryKeys.assets.detail(id ?? ''),
    queryFn: () => assetsApi.get(id!),
    enabled: Boolean(id),
  });
}

export function useCreateAsset() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (payload: AssetRequest) => assetsApi.create(payload),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.assets.lists() }),
  });
}

export function useUpdateAsset() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: AssetRequest }) =>
      assetsApi.update(id, payload),
    onSuccess: (data, { id }) => {
      qc.setQueryData(queryKeys.assets.detail(id), data);
      qc.invalidateQueries({ queryKey: queryKeys.assets.lists() });
    },
  });
}

export function useDeleteAsset() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => assetsApi.delete(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.assets.all }),
  });
}

export function useAttachments(assetId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.assets.attachments(assetId ?? ''),
    queryFn: () => assetsApi.listAttachments(assetId!),
    enabled: Boolean(assetId),
  });
}

export function useUploadAttachment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ assetId, file }: { assetId: string; file: File }) =>
      assetsApi.uploadAttachment(assetId, file),
    onSuccess: (_data, { assetId }) =>
      qc.invalidateQueries({ queryKey: queryKeys.assets.attachments(assetId) }),
  });
}

export function useDeleteAttachment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (attachmentId: string) => assetsApi.deleteAttachment(attachmentId),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.assets.all }),
  });
}
