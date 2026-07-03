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
  function writeNow () {
    clearTimeout(timer)
    timer = null
    try {
      fs.mkdirSync(path.dirname(filePath), { recursive: true })
      fs.writeFileSync(filePath + '.tmp', JSON.stringify(data, null, 2))
      fs.renameSync(filePath + '.tmp', filePath) // escritura atómica
    } catch (err) {
      console.error('[store] no se pudo guardar:', err.message)
    }
  }
  function persist () {
    clearTimeout(timer)
    timer = setTimeout(writeNow, 400)
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
        // Fusionamos preservando la lista de propietarios existente
        const owners = existing.owners || []
        Object.assign(existing, entry)
        existing.owners = Array.from(new Set([...owners, ...(entry.owners || [])]))
      } else {
        entry.owners = entry.owners || []
        data.push(entry)
      }
      persist()
    },
    // Añade un propietario a un torrent (idempotente)
    addOwner (infoHash, userId) {
      const e = data.find((x) => x.infoHash === infoHash)
      if (!e || !userId) return
      e.owners = e.owners || []
      if (!e.owners.includes(userId)) {
        e.owners.push(userId)
        persist()
      }
    },
    // Quita un propietario; devuelve cuántos quedan
    removeOwner (infoHash, userId) {
      const e = data.find((x) => x.infoHash === infoHash)
      if (!e) return 0
      e.owners = (e.owners || []).filter((o) => o !== userId)
      persist()
      return e.owners.length
    },
    getOwners (infoHash) {
      const e = data.find((x) => x.infoHash === infoHash)
      return e ? (e.owners || []).slice() : []
    },
    isOwner (infoHash, userId) {
      const e = data.find((x) => x.infoHash === infoHash)
      return !!e && (e.owners || []).includes(userId)
    },
    // Vuelca a disco de forma síncrona (para tests y apagado limpio)
    flush () { writeNow() },
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
