import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import {
  getCareNote,
  saveCareNote,
  getTemplates,
  createTemplate,
  updateTemplate,
  deleteTemplate,
  applyTemplate
} from '../api/careApi';
import type {
  CareNoteDto,
  CareTemplateDto,
  CareNoteItemDto
} from '../api/careApi';

export const useCareNoteQuery = (sitterId: string, ownerId: string) => {
  return useQuery<CareNoteDto, Error>({
    queryKey: ['care-note', sitterId, ownerId],
    queryFn: async () => {
      try {
        return await getCareNote(sitterId, ownerId);
      } catch (error) {
        if (axios.isAxiosError(error) && error.response?.status === 404) {
          // 404 防禦，回傳乾淨初始結構
          return {
            careNoteId: null,
            sitterId,
            ownerId,
            sections: {
              SERVICE: [],
              CONTACT: [],
              WARNING: [],
              PREFERENCE: [],
              HOSPITAL: [],
              OTHER: []
            }
          };
        }
        throw error;
      }
    },
    enabled: !!sitterId && !!ownerId
  });
};

export const useSaveCareNoteMutation = (sitterId: string, ownerId: string) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ items, idempotencyKey }: { items: CareNoteItemDto[]; idempotencyKey: string }) =>
      saveCareNote(sitterId, ownerId, items, idempotencyKey),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['care-note', sitterId, ownerId] });
    }
  });
};

export const useTemplatesQuery = () => {
  return useQuery({
    queryKey: ['care-templates'],
    queryFn: getTemplates
  });
};

export const useCreateTemplateMutation = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ dto, idempotencyKey }: { dto: CareTemplateDto; idempotencyKey: string }) =>
      createTemplate(dto, idempotencyKey),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['care-templates'] });
    }
  });
};

export const useUpdateTemplateMutation = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      templateId,
      dto,
      idempotencyKey
    }: {
      templateId: string;
      dto: CareTemplateDto;
      idempotencyKey: string;
    }) => updateTemplate(templateId, dto, idempotencyKey),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['care-templates'] });
    }
  });
};

export const useDeleteTemplateMutation = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ templateId, idempotencyKey }: { templateId: string; idempotencyKey: string }) =>
      deleteTemplate(templateId, idempotencyKey),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['care-templates'] });
    }
  });
};

export const useApplyTemplateMutation = (sitterId: string, ownerId: string) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ templateId, idempotencyKey }: { templateId: string; idempotencyKey: string }) =>
      applyTemplate(sitterId, ownerId, templateId, idempotencyKey),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['care-note', sitterId, ownerId] });
    }
  });
};
