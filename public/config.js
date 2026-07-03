// Configuración del frontend.

// Si alojas SOLO el frontend en GitHub Pages y el backend Node en otro
// dominio (p.ej. Render), pon aquí la URL del backend. Ejemplo:
//   window.TCV_API_BASE = 'https://mi-backend.onrender.com'
// Si sirves todo desde el mismo servidor Node, déjalo vacío.
window.TCV_API_BASE = ''

// Firebase (login con Google + sincronización de favoritos/historial/ajustes).
// Esta configuración NO es secreta: identifica el proyecto; la seguridad la
// imponen las reglas de Firestore y la verificación del token en el servidor.
// Déjalo en null para desactivar Firebase (solo cuentas locales).
window.TCV_FIREBASE = {
  apiKey: 'AIzaSyAOxy_O6R2BsYWNTe89njyzuIg3_O7weHY',
  authDomain: 'torrent-7dd4b.firebaseapp.com',
  projectId: 'torrent-7dd4b',
  storageBucket: 'torrent-7dd4b.firebasestorage.app',
  messagingSenderId: '457371422941',
  appId: '1:457371422941:web:f91a3dab9fadb03d834b34'
}
