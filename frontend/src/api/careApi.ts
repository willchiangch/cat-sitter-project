import axiosClient from './axiosClient';

export interface CareNoteItemDto {
  sectionType: 'SERVICE' | 'CONTACT' | 'WARNING' | 'PREFERENCE' | 'HOSPITAL' | 'OTHER';
  title: string;
  content: string;
}

export interface CareNoteItem extends CareNoteItemDto {
  id: string;
  sortOrder: number;
}

export interface CareNoteDto {
  careNoteId: string | null;
  sitterId: string;
  ownerId: string;
  sections: Record<string, CareNoteItem[]>;
}

export interface CareTemplateDto {
  name: string;
  items: CareNoteItemDto[];
}

export interface CareTemplate {
  id: string;
  name: string;
  items: CareNoteItem[];
  updatedAt: string;
}

export interface CareMedia {
  id: string;
  caption: string;
  mediaUrl: string;
  mediaType: string;
  createdAt: string;
}

export const getCareNote = async (sitterId: string, ownerId: string): Promise<CareNoteDto> => {
  const response = await axiosClient.get(`/care-notes/${sitterId}/${ownerId}`);
  return response.data.data;
};

export const saveCareNote = async (
  sitterId: string,
  ownerId: string,
  items: CareNoteItemDto[],
  idempotencyKey: string
): Promise<{ careNoteId: string }> => {
  const response = await axiosClient.put(
    `/care-notes/${sitterId}/${ownerId}`,
    { items },
    {
      headers: {
        'Idempotency-Key': idempotencyKey
      }
    }
  );
  return response.data.data;
};

export const getTemplates = async (): Promise<CareTemplate[]> => {
  const response = await axiosClient.get('/care-notes/templates');
  return response.data.data;
};

export const createTemplate = async (
  dto: CareTemplateDto,
  idempotencyKey: string
): Promise<{ templateId: string }> => {
  const response = await axiosClient.post('/care-notes/templates', dto, {
    headers: {
      'Idempotency-Key': idempotencyKey
    }
  });
  return response.data.data;
};

export const updateTemplate = async (
  templateId: string,
  dto: CareTemplateDto,
  idempotencyKey: string
): Promise<{ templateId: string }> => {
  const response = await axiosClient.put(`/care-notes/templates/${templateId}`, dto, {
    headers: {
      'Idempotency-Key': idempotencyKey
    }
  });
  return response.data.data;
};

export const deleteTemplate = async (templateId: string, idempotencyKey: string): Promise<void> => {
  await axiosClient.delete(`/care-notes/templates/${templateId}`, {
    headers: {
      'Idempotency-Key': idempotencyKey
    }
  });
};

export const applyTemplate = async (
  sitterId: string,
  ownerId: string,
  templateId: string,
  idempotencyKey: string
): Promise<void> => {
  await axiosClient.post(
    `/care-notes/${sitterId}/${ownerId}/apply-template/${templateId}`,
    {},
    {
      headers: {
        'Idempotency-Key': idempotencyKey
      }
    }
  );
};

export const getMedia = async (sitterId: string, ownerId: string): Promise<CareMedia[]> => {
  const response = await axiosClient.get(`/care-media/${sitterId}/${ownerId}`);
  return response.data.data;
};

export const uploadMedia = async (
  sitterId: string,
  ownerId: string,
  file: File,
  caption: string,
  idempotencyKey: string
): Promise<{ mediaId: string; mediaUrl: string }> => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('caption', caption);
  formData.append('mediaType', file.type.startsWith('video') ? 'VIDEO' : 'IMAGE');

  const response = await axiosClient.post(`/care-media/${sitterId}/${ownerId}`, formData, {
    headers: {
      'Idempotency-Key': idempotencyKey
    }
  });
  return response.data.data;
};

export const deleteMedia = async (mediaId: string, idempotencyKey: string): Promise<void> => {
  await axiosClient.delete(`/care-media/${mediaId}`, {
    headers: {
      'Idempotency-Key': idempotencyKey
    }
  });
};
