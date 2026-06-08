import { api } from 'src/boot/axios';

export const authApi = {
  /**
   * Login with credentials
   * @param {string} id - User identifier (email, phone, or username)
   * @param {string} password - User password
   * @param {number} loginCode - Login method: 1=email, 2=phone, 3=username
   * @returns {Promise} Response with JWT_CREATED or check.otp message
   */
  login(id, password, loginCode = 1) {
    return api.post('/authenticate', { id, password, loginCode });
  },

  /**
   * Verify OTP code for 2FA
   * @param {string} loginInfoId - Obfuscated login info ID from login response
   * @param {string} otp - 6-digit OTP code
   * @returns {Promise} Response with authentication result
   */
  verifyOtp(loginInfoId, otp) {
    return api.post('/otp', { loginInfoId, otp });
  },

  /**
   * Logout current session
   * @returns {Promise} Logout confirmation
   */
  logout() {
    return api.post('/api/logout');
  },

  /**
   * Check if user is authenticated
   * @returns {Promise} Authentication status
   */
  checkAuth() {
    return api.get('/v1/account/authenticate');
  }
};
