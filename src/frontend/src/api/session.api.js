import { api } from 'src/boot/axios';

export const sessionApi = {
  refresh() {
    return api.get('/refresh');
  },

  getDrills(library, params = {}) {
    return api.get('/api/session/drills', { params: { ...params, library } });
  },

  cloneDrill(drillId) {
    return api.post(`/api/session/drills/${drillId}/clone`);
  },
};
