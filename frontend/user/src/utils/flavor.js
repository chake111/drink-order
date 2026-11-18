/**
 * 口味值（逗号分隔字符串）转数组，供用户端口味选择共用。
 */
export function parseFlavorValue(value) {
  if (!value) return []
  return value.split(',').map(v => v.trim()).filter(v => v)
}