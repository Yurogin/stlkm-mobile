package fr.stlkm.hub

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Même principe que le hub PC : on ne tient aucune liste. On lit les dépôts publics du
// compte, et tout dépôt dont la dernière release contient un .apk est installable.

const val COMPTE = "Yurogin"
/** Le dépôt d'où vient ce hub : il se reconnaît dans sa propre liste, catalogue ou pas. */
const val MOI = "stlkm-mobile"
private const val API = "https://api.github.com"
private const val SUJET = "stlkm-android"   // sujet GitHub : évite d'aller voir les releases pour rien
private const val SANS_HUB = "stlkm-ignore" // le même que sur PC
private const val SCRUTES = 20              // dépôts récents fouillés quand aucun sujet ne le dit
private const val FRAIS = 6 * 60 * 60 * 1000L

/** Une appli du répertoire, telle qu'on l'affiche. */
data class App(
    val id: String,
    val nom: String,
    val resume: String,
    val depot: String,
    val version: String,          // le tag de la release, sans le v
    val apk: String,              // l'adresse de téléchargement
    val taille: Long,
    val paquet: String? = null,   // connu une fois installé par le hub
    val installee: String? = null,
) {
    /** Vrai quand le répertoire propose plus récent que ce qui est installé. */
    val aMettreAJour get() = installee != null && plusRecent(version, installee)
}

/** Compare par nombres : 1.10 vient après 1.9, ce qu'une comparaison de texte raterait. */
fun plusRecent(distante: String, locale: String): Boolean {
    val a = nombres(distante)
    val b = nombres(locale)
    if (a.isEmpty()) return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun nombres(v: String) = Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

object Store {
    private lateinit var prefs: android.content.SharedPreferences

    fun init(c: Context) {
        prefs = c.getSharedPreferences("hub", Context.MODE_PRIVATE)
    }

    /** Le paquet qu'a posé une fiche : appris à l'installation, gardé pour reconnaître la suite. */
    fun paquetDe(id: String): String? = prefs.getString("paquet/$id", null)

    fun retientPaquet(id: String, paquet: String) = prefs.edit().putString("paquet/$id", paquet).apply()

    /** La liste, relue chez GitHub ou reprise du cache si elle est fraîche. */
    suspend fun liste(c: Context, force: Boolean = false): List<App> = withContext(Dispatchers.IO) {
        val age = System.currentTimeMillis() - prefs.getLong("vu", 0)
        val cache = prefs.getString("liste", null)
        if (!force && cache != null && age in 0..FRAIS) {
            return@withContext lis(cache).map { installee(c, it) }
        }
        val apps = cherche(force)
        if (apps.isNotEmpty()) {
            prefs.edit().putString("liste", ecris(apps)).putLong("vu", System.currentTimeMillis()).apply()
        }
        apps.map { installee(c, it) }
    }

    /** Ce que le téléphone a déjà, pour cette fiche. */
    private fun installee(c: Context, a: App): App {
        // Le catalogue sait le nom de paquet ; sinon on l'a appris en installant ; sinon on devine.
        val paquet = a.paquet ?: paquetDe(a.id) ?: reconnait(c, a.nom) ?: return a
        return try {
            @Suppress("DEPRECATION")
            val info = c.packageManager.getPackageInfo(paquet, 0)
            a.copy(paquet = paquet, installee = info.versionName ?: "0")
        } catch (e: PackageManager.NameNotFoundException) {
            a.copy(paquet = paquet, installee = null)
        }
    }

    /** Une appli posée avant que le hub existe : on la reconnaît à son nom, faute de mieux.
     *  Le champ `android` du catalogue donnera un jour le nom de paquet sans deviner. */
    private fun reconnait(c: Context, nom: String): String? {
        val cherche = nom.lowercase().replace("-", "").replace("_", "")
        val lanceurs = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val trouves = try { c.packageManager.queryIntentActivities(lanceurs, 0) } catch (e: Exception) { return null }
        for (r in trouves) {
            val paquet = r.activityInfo?.packageName ?: continue
            val etiquette = try { r.loadLabel(c.packageManager).toString() } catch (e: Exception) { "" }
            val comme = etiquette.lowercase().replace(" ", "").replace("-", "")
            if (comme == cherche || paquet.lowercase().endsWith(".$cherche")) return paquet
        }
        return null
    }

    /** La liste, en payant le moins d'appels possible à GitHub.
     *
     *  Le catalogue dit déjà quelles applis ont un APK : les interroger coûte une demande
     *  chacune, là où fouiller les dépôts en coûte une par dépôt. On ne fouille donc que
     *  sur demande — le bouton Actualiser — pour voir ce que le catalogue ignore encore. */
    private fun cherche(force: Boolean): List<App> {
        val cat = Catalogue.telecharge() ?: Catalogue.VIDE

        val connues = cat.fiches.values
            .filter { it.surMobile && it.apk != null && it.depot != null && it.id !in cat.masques }
            .mapNotNull { f -> depuisRelease(f.depot!!, f.nom ?: f.id, "", f) }
        if (!force && connues.isNotEmpty()) return connues

        val tout = LinkedHashMap<String, App>()
        for (a in connues) tout[a.id] = a
        for (a in decouvre(cat)) tout.putIfAbsent(a.id, a)
        return tout.values.toList()
    }

    /** Ce que les dépôts racontent, pour les applis que le catalogue ne connaît pas encore. */
    private fun decouvre(cat: Catalogue): List<App> {
        val depots = json("$API/users/$COMPTE/repos?per_page=100&sort=pushed&type=owner") as? JSONArray
            ?: return emptyList()
        val retenus = ArrayList<Pair<JSONObject, Fiche?>>()
        for (i in 0 until depots.length()) {
            val d = depots.optJSONObject(i) ?: continue
            if (d.optBoolean("fork") || d.optBoolean("archived") || d.optLong("size") == 0L) continue
            val id = d.optString("name").lowercase()
            if (id in cat.masques) continue
            val f = cat.fiches[id]
            if (f != null && !f.surMobile) continue   // une fiche rangée « PC seulement »
            if (f?.apk != null) continue              // déjà servie par le catalogue
            val sujets = d.optJSONArray("topics")?.let { s -> List(s.length()) { s.optString(it) } } ?: emptyList()
            if (SANS_HUB in sujets) continue
            // Le sujet dit « j'ai un APK » ; sinon on ne regarde que les dépôts récemment
            // poussés, pour ne pas épuiser les 60 appels par heure que GitHub accorde.
            if (SUJET in sujets || retenus.size < SCRUTES) retenus.add(d to f)
        }
        return retenus.mapNotNull { (d, f) ->
            val nom = d.optString("name").ifBlank { return@mapNotNull null }
            val resume = d.optString("description").takeIf { it.isNotBlank() && it != "null" } ?: ""
            depuisRelease("$COMPTE/$nom", nom, resume, f)
        }
    }

    /** L'appli que donne la dernière release d'un dépôt, ou rien s'il n'y a pas d'APK. */
    private fun depuisRelease(depot: String, nom: String, resume: String, corrige: Fiche?): App? {
        val releases = json("$API/repos/$depot/releases?per_page=10") as? JSONArray ?: return null
        for (i in 0 until releases.length()) {
            val r = releases.optJSONObject(i) ?: continue
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val assets = r.optJSONArray("assets") ?: continue
            val apks = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk", true) }
            // Le catalogue nomme l'APK à prendre ; sinon on écarte celui de la montre.
            val a = corrige?.apk?.let { voulu -> apks.firstOrNull { it.optString("name").equals(voulu, true) } }
                ?: apks.firstOrNull { !it.optString("name").contains("montre", true) &&
                                      !it.optString("name").contains("wear", true) }
                ?: apks.firstOrNull() ?: continue
            return App(
                id = depot.substringAfterLast("/").lowercase(),
                nom = corrige?.nom ?: nom,
                resume = corrige?.resume ?: resume,
                depot = depot,
                version = r.optString("tag_name").trimStart('v', 'V'),
                apk = a.optString("browser_download_url"),
                taille = a.optLong("size"),
                paquet = corrige?.paquet,
            )
        }
        return null
    }

    private fun json(url: String): Any? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "STLKM-hub")
        }
        try {
            if (c.responseCode != 200) null
            else JSONArray("[" + c.inputStream.bufferedReader().use { it.readText() } + "]").opt(0)
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    // ---------- cache ----------
    private fun ecris(apps: List<App>) = JSONArray().apply {
        for (a in apps) put(JSONObject()
            .put("id", a.id).put("nom", a.nom).put("resume", a.resume).put("depot", a.depot)
            .put("version", a.version).put("apk", a.apk).put("taille", a.taille)
            .put("paquet", a.paquet ?: ""))
    }.toString()

    private fun lis(s: String): List<App> = try {
        val t = JSONArray(s)
        (0 until t.length()).mapNotNull { t.optJSONObject(it) }.map {
            App(it.optString("id"), it.optString("nom"), it.optString("resume"), it.optString("depot"),
                it.optString("version"), it.optString("apk"), it.optLong("taille"),
                it.optString("paquet").takeIf { p -> p.isNotBlank() })
        }
    } catch (e: Exception) {
        emptyList()
    }
}
