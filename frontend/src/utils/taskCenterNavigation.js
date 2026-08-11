export function taskCenterNavigation(currentFullPath, returnFullPath = '') {
  const current = String(currentFullPath || '/')
  const currentPath = current.split(/[?#]/, 1)[0]
  if (currentPath === '/tasks' || currentPath.startsWith('/tasks/')) {
    return { target: String(returnFullPath || '/'), nextReturnPath: '' }
  }
  return { target: '/tasks', nextReturnPath: current }
}
