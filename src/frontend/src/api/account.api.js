import { api } from 'src/boot/axios';

export const accountApi = {
  /**
   * Register a new user account
   * @param {Object} userData - Registration data (login, password, firstName, lastName, email, etc.)
   * @returns {Promise} Registration result
   */
  register(userData) {
    return api.post('/v1/account/register', userData);
  },

  /**
   * Resend account activation email
   * @param {string} login - User login identifier
   * @param {string} password - User password
   * @returns {Promise} Resend confirmation
   */
  resendActivation(login, password) {
    return api.post('/v1/account/regislink', null, {
      params: { login, password }
    });
  },

  /**
   * Activate account with activation key
   * @param {string} key - Activation key from email
   * @returns {Promise} Activation result
   */
  activate(key) {
    return api.post('/v1/account/activate', null, {
      params: { key }
    });
  },

  /**
   * Request password reset email
   * @param {string} loginId - User login identifier
   * @param {string} dob - Date of birth (yyyy-MM-dd)
   * @param {string} currentEmail - User email address
   * @returns {Promise} Password reset initiation result
   */
  requestPasswordReset(loginId, dob, currentEmail) {
    return api.post('/v1/account/reset_password/init', {
      loginId, dob, currentEmail
    });
  },

  /**
   * Complete password reset with new password
   * @param {string} key - Password reset key from email
   * @param {string} password - New password
   * @returns {Promise} Password reset completion result
   */
  resetPassword(key, password) {
    return api.post('/v1/account/reset_password/finish', {
      key, password
    });
  },

  /**
   * Get current user account details
   * @returns {Promise} Account data
   */
  getAccount() {
    return api.get('/v1/account/');
  }
};
