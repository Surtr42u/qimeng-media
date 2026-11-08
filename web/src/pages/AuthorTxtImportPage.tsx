import { Navigate } from 'react-router'

/** Existing bookmarks land on the author tab of the unified workspace. */
export default function AuthorTxtImportPage() {
  return <Navigate to="/app/maintenance/files/backup?tab=authors" replace />
}
