import { api } from 'src/boot/axios';

export const sessionApi = {
  /**
   * Refresh the current session
   * Requires authentication (cookie-based)
   * @returns {Promise} Session refresh result
   */
  refresh() {
    return api.get('/refresh');
  }
};
