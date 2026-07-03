// Persistencia sencilla de los torrents añadidos, para reanudarlos al
// reiniciar. Guarda un JSON con { infoHash, magnetURI, name, paused, addedAt }.

import fs from 'fs'
import path from 'path'

export function createStore (filePath) {
  let data = []
  try {
    const raw = fs.readFileSync(filePath, 'utf8')
    const parsed = JSON.parse(raw)
    if (Array.isArray(parsed)) data = parsed
  } catch {
    // fichero inexistente o corrupto -> empezamos vacío
  }

  let timer = null
  function persist () {
    clearTimeout(timer)
    timer = setTimeout(() => {
      try {
        fs.mkdirSync(path.dirname(filePath), { recursive: true })
        fs.writeFileSync(filePath + '.tmp', JSON.stringify(data, null, 2))
        fs.renameSync(filePath + '.tmp', filePath) // escritura atómica
      } catch (err) {
        console.error('[store] no se pudo guardar:', err.message)
      }
    }, 400)
    if (timer.unref) timer.unref()
  }

  return {
    all () {
      return data.map((e) => ({ ...e }))
    },
    add (entry) {
      if (!entry || !entry.infoHash) return
      const existing = data.find((e) => e.infoHash === entry.infoHash)
      if (existing) {
        Object.assign(existing, entry)
      } else {
        data.push(entry)
      }
      persist()
    },
    update (infoHash, patch) {
      const e = data.find((x) => x.infoHash === infoHash)
      if (e) {
        Object.assign(e, patch)
        persist()
      }
    },
    remove (infoHash) {
      const i = data.findIndex((e) => e.infoHash === infoHash)
      if (i >= 0) {
        data.splice(i, 1)
        persist()
      }
    }
  }
}
