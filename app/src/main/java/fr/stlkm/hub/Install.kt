package fr.stlkm.hub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Télécharger l'APK, puis le tendre à l'installateur d'Android : c'est lui qui demande
 *  confirmation et qui installe. Le hub ne pose jamais rien tout seul. */
object Install {

    /** Android exige une autorisation par appli pour proposer des installations. */
    fun autorise(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || c.packageManager.canRequestPackageInstalls()

    fun demandeAutorisation(c: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            c.startActivity(i)
        } catch (e: Exception) {
            c.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Rend le fichier téléchargé, ou rien. `avance` va de 0 à 1. */
    suspend fun telecharge(c: Context, app: App, avance: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        val dossier = File(c.cacheDir, "apk").apply { mkdirs() }
        val fichier = File(dossier, app.id + "-" + app.version + ".apk")
        if (fichier.exists() && app.taille > 0 && fichier.length() == app.taille) return@withContext fichier
        try {
            val co = (URL(app.apk).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "STLKM-hub")
            }
            try {
                if (co.responseCode != 200) return@withContext null
                val total = if (app.taille > 0) app.taille else co.contentLengthLong
                var recu = 0L
                co.inputStream.use { entree ->
                    fichier.outputStream().use { sortie ->
                        val tampon = ByteArray(64 * 1024)
                        while (true) {
                            val n = entree.read(tampon)
                            if (n < 0) break
                            sortie.write(tampon, 0, n)
                            recu += n
                            if (total > 0) avance(recu.toFloat() / total)
                        }
                    }
                }
            } finally {
                co.disconnect()
            }
            fichier
        } catch (e: Exception) {
            fichier.delete()
            null
        }
    }

    /** Le nom de paquet contenu dans l'APK : c'est ainsi qu'on reconnaîtra l'appli ensuite. */
    fun paquetDe(c: Context, apk: File): String? = try {
        @Suppress("DEPRECATION")
        c.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
    } catch (e: Exception) {
        null
    }

    fun ouvreInstallateur(c: Context, apk: File) {
        val uri = FileProvider.getUriForFile(c, c.packageName + ".fichiers", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
    }

    /** Ouvrir une appli déjà installée, quand elle a un écran. */
    fun lance(c: Context, paquet: String): Boolean {
        val i = c.packageManager.getLaunchIntentForPackage(paquet) ?: return false
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
