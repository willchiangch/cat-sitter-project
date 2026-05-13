export interface Plan {
  id: string;
  name: string;
  price: number;
  description: string;
}

export interface Pet {
  id: string;
  name: string;
  type: string;
  avatar?: string;
}

export interface BookingItem {
  planId: string;
  dates: string[];
  timesPerDay: number;
}

export interface BookingState {
  sitterId: string;
  items: BookingItem[];
  selectedPetIds: string[];
  notes: string;
  totalAmount: number;
}
