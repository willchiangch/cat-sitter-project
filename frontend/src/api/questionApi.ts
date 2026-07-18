import axiosClient from './axiosClient';

export type QuestionAnswerType = 'RADIO' | 'CHECKBOX' | 'INPUT' | 'TEXTAREA';

export interface SitterQuestion {
  id?: string;
  questionText: string;
  answerType: QuestionAnswerType;
  options: string[];
  required: boolean;
  sortOrder?: number;
  isActive?: boolean;
  version?: number;
}

export interface SitterQuestionSortRequest {
  questionIds: string[];
}

export const getMyQuestions = async (): Promise<SitterQuestion[]> => {
  const response = await axiosClient.get('/sitter/questions');
  return response.data;
};

export const getActiveQuestionsForBooking = async (sitterId: string): Promise<SitterQuestion[]> => {
  const response = await axiosClient.get(`/sitters/${sitterId}/questions`);
  return response.data;
};

export const createQuestion = async (question: SitterQuestion): Promise<SitterQuestion> => {
  const response = await axiosClient.post('/sitter/questions', question);
  return response.data.data;
};

export const updateQuestion = async (
  questionId: string,
  question: SitterQuestion
): Promise<SitterQuestion> => {
  const response = await axiosClient.put(`/sitter/questions/${questionId}`, question);
  return response.data.data;
};

export const deleteQuestion = async (questionId: string): Promise<void> => {
  await axiosClient.delete(`/sitter/questions/${questionId}`);
};

export const toggleQuestionActive = async (
  questionId: string,
  active: boolean
): Promise<SitterQuestion> => {
  const response = await axiosClient.put(`/sitter/questions/${questionId}/active`, { active });
  return response.data.data;
};

export const sortQuestions = async (request: SitterQuestionSortRequest): Promise<void> => {
  await axiosClient.post('/sitter/questions/sort', request);
};
