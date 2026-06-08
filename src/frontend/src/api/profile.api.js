import { api } from 'src/boot/axios';

export const profileApi = {
  /**
   * Get current user profile details
   * @returns {Promise} UserDto (email, phone, firstName, lastName, title, gender, nationalId, langKey, otpEnabled, etc.)
   */
  getProfile() {
    return api.get('/api/account/profile');
  },

  /**
   * Update user email address (requires verification)
   * @param {string} oldEmail - Current email address
   * @param {string} newEmail - New email address
   * @param {string} password - Current password for verification
   * @returns {Promise} Email update initiation result
   */
  updateEmail(oldEmail, newEmail, password) {
    return api.put('/api/account/email', { oldEmail, newEmail, password });
  },

  /**
   * Update user password
   * @param {string} currentPassword - Current password
   * @param {string} newPassword - New password
   * @returns {Promise} Password update result
   */
  updatePassword(currentPassword, newPassword) {
    return api.put('/api/account/password', { currentPassword, newPassword });
  },

  /**
   * Update user phone number
   * @param {string} phone - New phone number
   * @returns {Promise} Phone update result
   */
  updatePhone(phone) {
    return api.put('/api/account/phone', { phone });
  },

  /**
   * Update user address
   * @param {Object} addressData - Address data
   * @param {string} addressData.name - Address label (e.g., HOME, WORK)
   * @param {string} [addressData.companyName] - Company name (optional)
   * @param {string} addressData.addressLine1 - Primary address line
   * @param {string} [addressData.addressLine2] - Secondary address line (optional)
   * @param {string} [addressData.addressLine3] - Tertiary address line (optional)
   * @param {string} addressData.city - City
   * @param {string} addressData.stateProvince - State or province
   * @param {string} addressData.postalCode - Postal code
   * @param {string} addressData.country - Country
   * @returns {Promise} Address update result
   */
  updateAddress(addressData) {
    return api.put('/api/account/address', addressData);
  },

  /**
   * Update user personal information
   * @param {Object} infoData - Personal information data
   * @param {string} [infoData.firstName] - First name (optional)
   * @param {string} [infoData.lastName] - Last name (optional)
   * @param {string} [infoData.langKey] - Preferred language key (optional)
   * @param {string} [infoData.nationalId] - National ID (optional)
   * @param {string} [infoData.gender] - Gender (optional)
   * @param {string} [infoData.title] - Title (optional)
   * @returns {Promise} Info update result
   */
  updateInfo(infoData) {
    return api.put('/api/account/info', infoData);
  },

  /**
   * Toggle two-factor authentication
   * @param {boolean} enabled - Whether to enable or disable 2FA
   * @param {string} password - Current password for verification
   * @returns {Promise} 2FA toggle result
   */
  toggle2fa(enabled, password) {
    return api.put('/api/account/2fa', { enabled, password });
  }
};
