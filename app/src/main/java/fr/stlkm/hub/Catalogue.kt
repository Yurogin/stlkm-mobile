package fr.stlkm.hub

import java.net.HttpURLConnection
import java.net.URL

// Le même fichier que celui du hub PC : Yurogin/stlkm-catalog. Il corrige ce que la
// découverte devine — le nom affiché, le résumé, quel APK prendre, et le nom de paquet
// de l'appli une fois posée. Tout y est facultatif : sans fiche, la découverte suffit.

private const val PUBLIE =
    "https://raw.githubusercontent.com/Yurogin/stlkm-catalog/main/catalog.toml"

/** Ce que le catalogue dit d'un projet. Chaque champ peut manquer. */
data class Fiche(
    val id: String,
    val nom: String? = null,
    val resume: String? = null,
    val plateformes: List<String> = emptyList(),
    val apk: String? = null,       // le nom de l'asset à prendre dans la release
    val paquet: String? = null,    // ce qui s'installe, pour reconnaître l'appli
) {
    /** Une fiche sans restriction est partout ; c'est le cas courant. */
    val surMobile get() = plateformes.isEmpty() || "android" in plateformes
}

data class Catalogue(val fiches: Map<String, Fiche>, val masques: Set<String>) {
    companion object {
        val VIDE = Catalogue(emptyMap(), emptySet())

        fun telecharge(): Catalogue? = try {
            val c = (URL(PUBLIE).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "STLKM-hub")
            }
            try {
                if (c.responseCode != 200) null
                else lis(c.inputStream.bufferedReader().use { it.readText() })
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null   // pas de réseau : on se débrouille avec ce que GitHub raconte
        }

        /** On ne lit que ce dont le téléphone a besoin ; le reste du TOML est ignoré
         *  sans bruit, pour qu'un champ ajouté un jour côté PC ne casse rien ici. */
        fun lis(toml: String): Catalogue {
            val fiches = LinkedHashMap<String, Fiche>()
            var masques = emptySet<String>()
            var enCours: Fiche? = null

            fun boucle() {
                enCours?.let { if (it.id.isNotBlank()) fiches[it.id] = it }
                enCours = null
            }

            for (brute in toml.lineSequence()) {
                val ligne = sansCommentaire(brute).trim()
                if (ligne.isEmpty()) continue
                if (ligne.startsWith("[[project]]")) {
                    boucle()
                    enCours = Fiche(id = "")
                    continue
                }
                if (ligne.startsWith("[")) {   // une autre table : on sort de la fiche
                    boucle()
                    continue
                }
                val cle = ligne.substringBefore("=").trim()
                val valeur = ligne.substringAfter("=", "").trim()
                if (valeur.isEmpty()) continue

                val f = enCours
                if (f == null) {
                    if (cle == "hidden") masques = liste(valeur).toSet()
                    continue
                }
                enCours = when (cle) {
                    "id" -> f.copy(id = texte(valeur))
                    "name" -> f.copy(nom = texte(valeur))
                    "summary" -> f.copy(resume = texte(valeur))
                    "platforms" -> f.copy(plateformes = liste(valeur))
                    "source" -> f.copy(apk = dansTable(valeur, "asset", "android"))
                    "launch" -> f.copy(paquet = dansTable(valeur, "exec", "android"))
                    else -> f
                }
            }
            boucle()
            return Catalogue(fiches, masques)
        }

        /** Retire un commentaire de fin de ligne, sans couper dedans une chaîne. */
        private fun sansCommentaire(ligne: String): String {
            var dansTexte = false
            for (i in ligne.indices) {
                val c = ligne[i]
                if (c == '"') dansTexte = !dansTexte
                if (c == '#' && !dansTexte) return ligne.substring(0, i)
            }
            return ligne
        }

        private fun texte(v: String) = v.trim().trim('"')

        private fun liste(v: String) =
            v.trim().removePrefix("[").removeSuffix("]")
                .split(",").map { texte(it) }.filter { it.isNotEmpty() }

        /** `{ kind = "…", asset = { windows = "a", android = "b" } }` → "b". */
        private fun dansTable(valeur: String, table: String, cle: String): String? {
            val depart = valeur.indexOf("$table = {").takeIf { it >= 0 }
                ?: valeur.indexOf("$table={").takeIf { it >= 0 }
                ?: return null
            val ouvre = valeur.indexOf('{', depart)
            val ferme = valeur.indexOf('}', ouvre).takeIf { it > 0 } ?: return null
            val dedans = valeur.substring(ouvre + 1, ferme)
            for (morceau in dedans.split(",")) {
                val k = morceau.substringBefore("=").trim()
                if (k == cle) return texte(morceau.substringAfter("=", ""))
            }
            return null
        }
    }
}
