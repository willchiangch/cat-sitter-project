import imageCompression from 'browser-image-compression';

/**
 * Compresses an image file for high-fidelity web use.
 * Targets 1920px max dimension and 0.8 quality to balance aesthetics and performance.
 * @param {File} file - The original image file.
 * @returns {Promise<File>} - The compressed image file.
 */
export const compressImage = async (file) => {
  // Only compress if larger than 500KB to avoid unnecessary processing
  if (file.size < 500 * 1024) {
    return file;
  }

  const options = {
    maxSizeMB: 1,            // Target max 1MB
    maxWidthOrHeight: 1920, // High-def but web-optimized
    useWebWorker: true,
    initialQuality: 0.8,
    fileType: 'image/jpeg'  // Standardize to JPEG for consistent behavior
  };

  try {
    console.log(`[ImageUtils] Starting compression for ${file.name} (${(file.size / 1024).toFixed(2)} KB)`);
    const compressedFile = await imageCompression(file, options);
    console.log(`[ImageUtils] Compression finished (${(compressedFile.size / 1024).toFixed(2)} KB)`);
    return compressedFile;
  } catch (error) {
    console.error('[ImageUtils] Compression failed:', error);
    return file; // Fallback to original on error
  }
};
