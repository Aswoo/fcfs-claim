import { api } from './api';

export const queueService = {
  enter: async (userId: number, eventId: number) => {
    const res = await api.post('/api/v1/queue/enter', { userId, eventId });
    return res.data.data as { rank: number };
  },

  getStatus: async (userId: number, eventId: number) => {
    const res = await api.get('/api/v1/queue/status', {
      params: { userId, eventId },
    });
    return res.data.data as { isReady: boolean; rank: number; token: string | null };
  },

  simulatePrecedingUsers: async (count: number, eventId: number) => {
    const requests = Array.from({ length: count }, () => {
      const fakeUserId = Math.floor(Math.random() * 9000000) + 1000000;
      return api.post('/api/v1/queue/enter', { userId: fakeUserId, eventId });
    });
    await Promise.all(requests);
  },

  reset: async () => {
    await api.post('/api/v1/admin/reset');
  },
};
