// Carga un archivo .env (si existe) sin dependencias externas.
// Formato: líneas KEY=VALUE, admite comentarios (#) y comillas.
// No sobreescribe variables ya definidas en el entorno.
import fs from 'node:fs'

export function loadEnv (file = '.env') {
  let text
  try {
    text = fs.readFileSync(file, 'utf8')
  } catch {
    return // no hay .env: no pasa nada
  }
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const eq = line.indexOf('=')
    if (eq < 0) continue
    const key = line.slice(0, eq).trim()
    let val = line.slice(eq + 1).trim()
    // quita comillas envolventes
    if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
      val = val.slice(1, -1)
    }
    if (key && process.env[key] === undefined) process.env[key] = val
  }
}
