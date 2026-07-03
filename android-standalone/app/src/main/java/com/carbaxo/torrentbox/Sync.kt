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
 * Sincronización con la MISMA cuenta que la app del PC: login con Google
 * (Firebase Auth) y favoritos guardados en Firestore en el mismo documento
 * (users/{uid}), esquema { states: { default: { favorites: [...] } } }.
 *
 * Requiere que la app Android esté registrada en Firebase con su SHA-1 y el
 * google-services.json en app/. El ID de cliente web va en BuildConfig.
 */
object Sync {
    data class Fav(
        val id: String,           // "tmdb:<id>"
        val tmdbId: Int,
        val title: String,
        val year: String,
        val poster: String?,
        val type: String,         // "movie" | "series"
        val rating: Double
    )

    private val main = Handler(Looper.getMainLooper())
    private fun onMain(b: () -> Unit) = main.post(b)

    private var gsc: GoogleSignInClient? = null

    /** Disponible solo si la compilación trae el ID de cliente web. */
    val enabled: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    var email by mutableStateOf<String?>(null)
    val favorites = mutableStateListOf<Fav>()

    fun init(ctx: Context) {
        if (!enabled) return
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .requestEmail()
            .build()
        gsc = GoogleSignIn.getClient(ctx.applicationContext, gso)
        // Restaurar sesión previa
        FirebaseAuth.getInstance().currentUser?.let {
            email = it.email
            loadFavorites()
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
                    loadFavorites()
                    onDone(true, null)
                } else onDone(false, t.exception?.message ?: "Error de Firebase")
            }
        } catch (e: ApiException) {
            // status 10 = DEVELOPER_ERROR (falta registrar la SHA-1 en Firebase)
            onDone(false, "Google (código ${e.statusCode})")
        } catch (e: Throwable) {
            onDone(false, e.message ?: "Error")
        }
    }

    fun signOut() {
        FirebaseAuth.getInstance().signOut()
        gsc?.signOut()
        email = null
        favorites.clear()
    }

    private fun db() = FirebaseFirestore.getInstance()
    private fun uid() = FirebaseAuth.getInstance().currentUser?.uid

    @Suppress("UNCHECKED_CAST")
    private fun loadFavorites() {
        val u = uid() ?: return
        db().collection("users").document(u).get().addOnSuccessListener { snap ->
            val out = ArrayList<Fav>()
            try {
                val states = snap.get("states") as? Map<String, Any?>
                val def = states?.get("default") as? Map<String, Any?>
                val favs = def?.get("favorites") as? List<Map<String, Any?>>
                favs?.forEach { f ->
                    val id = f["id"]?.toString() ?: return@forEach
                    val tmdbId = Regex("(\\d+)").find(id)?.value?.toIntOrNull() ?: 0
                    out.add(
                        Fav(
                            id = id,
                            tmdbId = tmdbId,
                            title = f["title"]?.toString() ?: "",
                            year = f["year"]?.toString() ?: "",
                            poster = f["poster"]?.toString()?.takeIf { it.isNotBlank() && it != "null" },
                            type = if (f["type"]?.toString() == "series") "series" else "movie",
                            rating = (f["rating"] as? Number)?.toDouble() ?: 0.0
                        )
                    )
                }
            } catch (_: Throwable) {}
            onMain { favorites.clear(); favorites.addAll(out) }
        }
    }

    fun isFav(id: String) = favorites.any { it.id == id }

    /** Añade o quita de favoritos y sincroniza el documento (merge). */
    fun toggleFavorite(t: Tmdb.Title, onDone: (Boolean) -> Unit = {}) {
        val u = uid() ?: return onDone(false)
        val id = "tmdb:${t.tmdbId}"
        if (isFav(id)) favorites.removeAll { it.id == id }
        else favorites.add(0, Fav(id, t.tmdbId, t.title, t.year, t.poster, t.type, t.rating))
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val arr = favorites.map {
            mapOf(
                "id" to it.id, "title" to it.title, "year" to it.year,
                "poster" to it.poster, "type" to it.type, "rating" to it.rating, "addedAt" to iso
            )
        }
        val doc = mapOf("states" to mapOf("default" to mapOf("favorites" to arr)), "updatedAt" to iso)
        db().collection("users").document(u).set(doc, SetOptions.merge())
            .addOnCompleteListener { onDone(it.isSuccessful) }
    }
}
