export async function uploadFile(file) {
  const formData = new FormData()
  formData.append('file', file)
  const response = await fetch('/api/file/upload', { method: 'POST', body: formData })
  if (!response.ok) {
    const raw = await response.text().catch(() => '')
    if (raw) {
      try {
        throw new Error((JSON.parse(raw) || {}).message || raw)
      } catch (error) {
        if (!(error instanceof SyntaxError)) throw error
      }
    }
    throw new Error(`上传失败（HTTP ${response.status}）`)
  }
  return response.json()
}
