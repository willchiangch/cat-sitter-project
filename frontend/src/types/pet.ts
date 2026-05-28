export interface Pet {
  id?: string;
  ownerId?: string;
  name: string;
  species: string;
  gender?: string;
  neutered?: boolean;
  weight?: number;
  birthYear?: number;
  photoUrl?: string;
  medicalPersonalityNotes?: string;
  environmentalNotes?: string;
  version?: number;
}

export interface PetEditLog {
  id: string;
  petId: string;
  editorId: string;
  diffSummary: {
    editorRole: 'OWNER' | 'SITTER' | 'ADMIN';
    changes: {
      medicalPersonalityNotes?: {
        before: string;
        after: string;
      };
      environmentalNotes?: {
        before: string;
        after: string;
      };
    };
  };
  createdAt: string;
}
