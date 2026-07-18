import { useQuery } from '@tanstack/react-query';
import { getActiveQuestionsForBooking } from '../api/questionApi';
import type { SitterQuestion } from '../api/questionApi';

export const useActiveQuestionsQuery = (sitterId: string) => {
  return useQuery<SitterQuestion[], Error>({
    queryKey: ['sitter-active-questions', sitterId],
    queryFn: () => getActiveQuestionsForBooking(sitterId),
    staleTime: 5 * 60 * 1000,
    enabled: !!sitterId
  });
};
