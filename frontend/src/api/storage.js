import api from './index';
import axios from 'axios';

/**
 * Storage API client for WhiskerWatch GCS Integration.
 */
const storageApi = {
  /**
   * Request a signed URL from the backend for direct upload.
   * @param {string} fileName - Original filename.
   * @param {string} subFolder - Target directory (e.g., 'services/123').
   * @returns {Promise<string>} - The signed GCS URL.
   */
  getUploadUrl: async (fileName, subFolder) => {
    const response = await api.post('/v1/storage/upload-url', { fileName, subFolder });
    return response.data.uploadUrl;
  },

  /**
   * Upload a file directly to Google Cloud Storage using a signed URL.
   * @param {string} signedUrl - The signed URL from the backend.
   * @param {File|Blob} file - The file (ideally compressed) to upload.
   * @returns {Promise<void>}
   */
  uploadToGcs: async (signedUrl, file) => {
    // Note: Do NOT use the default 'api' (axios instance) here because 
    // it includes the Authorization header, which will cause GCS to reject the signature.
    // We use a clean axios instance.
    await axios.put(signedUrl, file, {
      headers: {
        'Content-Type': file.type,
      },
    });
  },

  /**
   * Helper to perform the full upload flow: get URL -> upload.
   * Returns the clean GCS path (before query params) for permanent storage.
   */
  performFullUpload: async (file, subFolder) => {
    const signedUrl = await storageApi.getUploadUrl(file.name, subFolder);
    await storageApi.uploadToGcs(signedUrl, file);
    
    // Extract the clean GCS object URL (strip query parameters)
    const cleanUrl = signedUrl.split('?')[0];
    return cleanUrl;
  }
};

export default storageApi;
