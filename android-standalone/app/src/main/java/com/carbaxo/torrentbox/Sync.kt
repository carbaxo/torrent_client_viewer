package com.carbaxo.torrentbox

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Sincronización con la MISMA cuenta que la app del PC (Firebase Auth + Google)
 * y el MISMO documento de Firestore que usa la web:
 *   users/{uid} = {
 *     profiles: [{ id, name, kids, avatar }],
 *     states:   { [profileId]: { favorites: [...], progress: [...], settings: {} } },
 *     account:  { rdToken }        // extensión: token de Real-Debrid compartido
 *   }
 *
 * Así los perfiles, favoritos y ajustes creados en la web aparecen aquí.
 */
object Sync {
    data class Fav(
        val id: String, val tmdbId: Int, val title: String, val year: String,
        val poster: String?, val type: String, val rating: Double
    )
    data class Profile(val id: String, val name: String, val avatar: String, val kids: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private fun onMain(b: () -> Unit) = main.post(b)

    private var gsc: GoogleSignInClient? = null
    private var appCtx: Context? = null

    val enabled: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    var email by mutableStateOf<String?>(null)
    val profiles = mutableStateListOf<Profile>()
    var activeProfile by mutableStateOf<Profile?>(null)
    val favorites = mutableStateListOf<Fav>()
    var loading by mutableStateOf(false)

    // Copia en memoria del documento para poder hacer merges por perfil
    private var doc: Map<String, Any?> = emptyMap()
    // callback opcional cuando llega el token RD desde la nube
    var onRdToken: ((String) -> Unit)? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        if (!enabled) return
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .requestEmail()
            .build()
        gsc = GoogleSignIn.getClient(ctx.applicationContext, gso)
        FirebaseAuth.getInstance().currentUser?.let {
            email = it.email
            loadDoc()
        }
    }

    fun signInIntent(): Intent? = gsc?.signInIntent

    fun onSignInResult(data: Intent?, onDone: (Boolean, String?) -> Unit) {
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            val cred = GoogleAuthProvider.getCredential(account.idToken, null)
            FirebaseAuth.getInstance().signInWithCredential(cred).addOnCompleteListener { t ->
                if (t.isSuccessful) {
                    email = FirebaseAuth.getInstance().currentUser?.email
                    loadDoc()
                    onDone(true, null)
                } else onDone(false, t.exception?.message ?: "Error de Firebase")
            }
        } catch (e: ApiException) {
            onDone(false, "Google (código ${e.statusCode})") // 10 = falta SHA-1 en Firebase
        } catch (e: Throwable) {
            onDone(false, e.message ?: "Error")
        }
    }

    fun signOut() {
        FirebaseAuth.getInstance().signOut()
        gsc?.signOut()
        email = null
        profiles.clear(); favorites.clear(); activeProfile = null; doc = emptyMap()
    }

    private fun db() = FirebaseFirestore.getInstance()
    private fun uid() = FirebaseAuth.getInstance().currentUser?.uid
    private fun iso() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun lastProfilePref(): String? =
        appCtx?.getSharedPreferences("tcv_prefs", Context.MODE_PRIVATE)?.getString("lastProfile", null)
    private fun rememberProfile(id: String) {
        appCtx?.getSharedPreferences("tcv_prefs", Context.MODE_PRIVATE)?.edit()?.putString("lastProfile", id)?.apply()
    }

    /** Lee el documento completo del usuario (perfiles + estados + cuenta). */
    @Suppress("UNCHECKED_CAST")
    fun loadDoc() {
        val u = uid() ?: return
        loading = true
        db().collection("users").document(u).get().addOnSuccessListener { snap ->
            val data = (snap.data ?: emptyMap<String, Any?>()) as Map<String, Any?>
            val profs = (data["profiles"] as? List<Map<String, Any?>>)?.mapNotNull { p ->
                val id = p["id"]?.toString() ?: return@mapNotNull null
                Profile(
                    id = id,
                    name = p["name"]?.toString() ?: "Perfil",
                    avatar = p["avatar"]?.toString()?.takeIf { it.isNotBlank() } ?: "🍿",
                    kids = p["kids"] == true
                )
            } ?: emptyList()

            // Token de Real-Debrid a nivel de cuenta (extensión de la app móvil)
            val account = data["account"] as? Map<String, Any?>
            val rdToken = account?.get("rdToken")?.toString()?.takeIf { it.isNotBlank() }

            onMain {
                doc = data
                profiles.clear(); profiles.addAll(profs)
                loading = false
                if (profs.isNotEmpty()) {
                    val pick = profs.firstOrNull { it.id == lastProfilePref() } ?: profs.first()
                    selectProfile(pick.id)
                }
                if (rdToken != null) onRdToken?.invoke(rdToken)
            }
        }.addOnFailureListener { onMain { loading = false } }
    }

    /** Adopta el estado (favoritos + ajustes) de un perfil concreto. */
    @Suppress("UNCHECKED_CAST")
    fun selectProfile(id: String) {
        val p = profiles.firstOrNull { it.id == id } ?: return
        activeProfile = p
        rememberProfile(id)
        val states = doc["states"] as? Map<String, Any?>
        val state = states?.get(id) as? Map<String, Any?>
        val favs = state?.get("favorites") as? List<Map<String, Any?>> ?: emptyList()
        val out = favs.mapNotNull { f ->
            val fid = f["id"]?.toString() ?: return@mapNotNull null
            Fav(
                id = fid,
                tmdbId = Regex("(\\d+)").find(fid)?.value?.toIntOrNull() ?: 0,
                title = f["title"]?.toString() ?: "",
                year = f["year"]?.toString() ?: "",
                poster = f["poster"]?.toString()?.takeIf { it.isNotBlank() && it != "null" },
                type = if (f["type"]?.toString() == "series") "series" else "movie",
                rating = (f["rating"] as? Number)?.toDouble() ?: 0.0
            )
        }
        favorites.clear(); favorites.addAll(out)

        // Progreso / vistos del perfil -> WatchStore
        val prog = state?.get("progress") as? List<Map<String, Any?>> ?: emptyList()
        runCatching { WatchStore.loadFromMaps(prog) }

        // Aplica el idioma del perfil (settings.language) al orden local
        val settings = state?.get("settings") as? Map<String, Any?>
        val cloudLang = Lang.fromTmdb(settings?.get("language")?.toString())
        if (cloudLang != null) {
            val rest = Prefs.languageOrder.filter { it != cloudLang }
            Prefs.setLanguageOrder(listOf(cloudLang) + rest)
        }
    }

    fun isFav(id: String) = favorites.any { it.id == id }

    // --- Gestión de perfiles desde el móvil (mismo esquema/límites que la web) ---
    private const val MAX_PROFILES = 5

    private fun writeProfiles(u: String, list: List<Profile>, onDone: (Boolean) -> Unit) {
        val arr = list.map { mapOf("id" to it.id, "name" to it.name, "avatar" to it.avatar, "kids" to it.kids) }
        db().collection("users").document(u)
            .set(mapOf("profiles" to arr, "updatedAt" to iso()), SetOptions.merge())
            .addOnCompleteListener { onDone(it.isSuccessful) }
    }

    fun addProfile(name: String, kids: Boolean, avatar: String, onDone: (Boolean, String?) -> Unit) {
        val u = uid() ?: return onDone(false, "Inicia sesión con Google")
        if (profiles.size >= MAX_PROFILES) return onDone(false, "Máximo $MAX_PROFILES perfiles")
        val nm = name.trim().take(24)
        if (nm.isBlank()) return onDone(false, "Escribe un nombre")
        val p = Profile(java.util.UUID.randomUUID().toString(), nm, avatar.ifBlank { if (kids) "🧒" else "🍿" }, kids)
        writeProfiles(u, profiles + p) { ok ->
            onMain { if (ok) profiles.add(p) }
            onDone(ok, if (ok) null else "No se pudo guardar")
        }
    }

    fun updateProfile(id: String, name: String, kids: Boolean, avatar: String, onDone: (Boolean, String?) -> Unit) {
        val u = uid() ?: return onDone(false, "Inicia sesión con Google")
        val idx = profiles.indexOfFirst { it.id == id }
        if (idx < 0) return onDone(false, "Perfil no encontrado")
        val nm = name.trim().take(24)
        if (nm.isBlank()) return onDone(false, "Escribe un nombre")
        val updated = profiles[idx].copy(name = nm, kids = kids, avatar = avatar.ifBlank { if (kids) "🧒" else "🍿" })
        val newList = profiles.toMutableList().apply { set(idx, updated) }
        writeProfiles(u, newList) { ok ->
            onMain {
                if (ok) {
                    profiles[idx] = updated
                    if (activeProfile?.id == id) activeProfile = updated
                }
            }
            onDone(ok, if (ok) null else "No se pudo guardar")
        }
    }

    fun removeProfile(id: String, onDone: (Boolean, String?) -> Unit) {
        val u = uid() ?: return onDone(false, "Inicia sesión con Google")
        if (profiles.size <= 1) return onDone(false, "Debe quedar al menos un perfil")
        val newList = profiles.filter { it.id != id }
        writeProfiles(u, newList) { ok ->
            onMain {
                if (ok) {
                    profiles.removeAll { it.id == id }
                    if (activeProfile?.id == id) profiles.firstOrNull()?.let { selectProfile(it.id) }
                }
            }
            onDone(ok, if (ok) null else "No se pudo guardar")
        }
    }

    /** Añade/quita favorito en el perfil activo y lo guarda (merge por perfil). */
    fun toggleFavorite(t: Tmdb.Title, onDone: (Boolean) -> Unit = {}) {
        val u = uid() ?: return onDone(false)
        val pid = activeProfile?.id ?: return onDone(false)
        val id = "tmdb:${t.tmdbId}"
        if (isFav(id)) favorites.removeAll { it.id == id }
        else favorites.add(0, Fav(id, t.tmdbId, t.title, t.year, t.poster, t.type, t.rating))
        val arr = favorites.map {
            mapOf(
                "id" to it.id, "title" to it.title, "year" to it.year,
                "poster" to it.poster, "type" to it.type, "rating" to it.rating, "addedAt" to iso()
            )
        }
        val docPatch = mapOf(
            "states" to mapOf(pid to mapOf("favorites" to arr)),
            "updatedAt" to iso()
        )
        db().collection("users").document(u).set(docPatch, SetOptions.merge())
            .addOnCompleteListener { onDone(it.isSuccessful) }
    }

    /** Guarda el progreso/vistos del perfil activo en la nube (merge). */
    fun saveProgressCloud(progress: List<Map<String, Any?>>) {
        val u = uid() ?: return
        val pid = activeProfile?.id ?: return
        db().collection("users").document(u)
            .set(mapOf("states" to mapOf(pid to mapOf("progress" to progress)), "updatedAt" to iso()), SetOptions.merge())
    }

    /** Guarda el token de Real-Debrid a nivel de cuenta para compartirlo entre dispositivos. */
    fun saveAccountRdToken(token: String) {
        val u = uid() ?: return
        db().collection("users").document(u)
            .set(mapOf("account" to mapOf("rdToken" to token), "updatedAt" to iso()), SetOptions.merge())
    }

    /**
     * Sube el idioma del perfil activo a states[pid].settings.language (mismo
     * campo que la web). No-op si no hay sesión o perfil activo.
     */
    fun saveSettingsLanguage(tmdbLang: String) {
        val u = uid() ?: return
        val pid = activeProfile?.id ?: return
        db().collection("users").document(u).set(
            mapOf("states" to mapOf(pid to mapOf("settings" to mapOf("language" to tmdbLang))), "updatedAt" to iso()),
            SetOptions.merge()
        )
    }
}
