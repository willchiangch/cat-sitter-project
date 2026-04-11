/**
 * Calculate Age from birthDate (YYYY-MM-DD format)
 * @param {string} birthDate 
 * @returns {string|null} - e.g. "2歲 3個月", "5個月", or null
 */
export const calculateAge = (birthDate) => {
  if (!birthDate) return null
  const birth = new Date(birthDate)
  const now = new Date()
  
  let years = now.getFullYear() - birth.getFullYear()
  let months = now.getMonth() - birth.getMonth()
  
  if (months < 0) {
    years--
    months += 12
  }
  
  if (years === 0) return `${months}個月`
  if (months === 0) return `${years}歲`
  return `${years}歲 ${months}個月`
}
