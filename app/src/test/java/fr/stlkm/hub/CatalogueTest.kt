package fr.stlkm.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le catalogue est écrit à la main autant que par l'Atelier : on lit un extrait
 *  du vrai fichier, tel qu'il est publié, commentaires compris. */
class CatalogueTest {

    private val extrait = """
        # Catalogue STLKM — schéma v1
        schema = 1

        # Dépôts que le répertoire n'affiche pas
        hidden = ["cleanfiles", "discordb", "yurogin"]

        [[project]]
        id = "klaxon"
        name = "Klaxon"
        summary = "Klaxonne tes potes d'ordinateur à ordinateur."
        source = { kind = "github-release", repo = "Yurogin/klaxon", asset = { windows = "Klaxon.exe", android = "Klaxon-telephone.apk" } }
        launch = { kind = "process", exec = { windows = "Klaxon.exe", android = "fr.stlkm.klaxon" } }

        [[project]]
        id = "stlkm-mobile"
        name = "STLKM mobile"
        platforms = ["android"]
        source = { kind = "github-release", repo = "Yurogin/stlkm-mobile", asset = { android = "STLKM.apk" } }
        launch = { kind = "process", exec = { android = "fr.stlkm.hub" } }

        [[project]]
        id = "perquiz"
        name = "Perquiz"
        source = { kind = "github-repo", repo = "Yurogin/Perquiz" }
        launch = { kind = "process", exec = { windows = "Perquiz.exe" } }
    """.trimIndent()

    private val cat = Catalogue.lis(extrait)

    @Test
    fun les_depots_masques_sont_lus() {
        assertEquals(setOf("cleanfiles", "discordb", "yurogin"), cat.masques)
    }

    @Test
    fun une_fiche_donne_son_apk_et_son_paquet() {
        val k = cat.fiches.getValue("klaxon")
        assertEquals("Klaxon", k.nom)
        assertEquals("Yurogin/klaxon", k.depot)
        assertEquals("Klaxon-telephone.apk", k.apk)
        assertEquals("fr.stlkm.klaxon", k.paquet)
        assertTrue(k.surMobile)
    }

    @Test
    fun une_fiche_sans_plateforme_est_partout() {
        assertTrue(cat.fiches.getValue("klaxon").plateformes.isEmpty())
        assertTrue(cat.fiches.getValue("klaxon").surMobile)
    }

    @Test
    fun une_fiche_rangee_mobile_le_dit() {
        val m = cat.fiches.getValue("stlkm-mobile")
        assertEquals(listOf("android"), m.plateformes)
        assertEquals("STLKM.apk", m.apk)
        assertEquals("fr.stlkm.hub", m.paquet)
        assertTrue(m.surMobile)
    }

    @Test
    fun une_fiche_pc_na_pas_dapk_android() {
        val p = cat.fiches.getValue("perquiz")
        assertEquals("Yurogin/Perquiz", p.depot)
        assertNull(p.apk)
        assertNull(p.paquet)
        assertTrue(p.surMobile)   // rien ne l'en empêche : c'est l'absence d'APK qui l'écarte
    }

    @Test
    fun un_commentaire_ne_coupe_pas_une_chaine() {
        val c = Catalogue.lis(
            """
            [[project]]
            id = "x"
            name = "Un # dans le nom"   # et un vrai commentaire
            """.trimIndent(),
        )
        assertEquals("Un # dans le nom", c.fiches.getValue("x").nom)
    }

    @Test
    fun les_versions_se_comparent_par_nombres() {
        assertTrue(plusRecent("1.10", "1.9"))
        assertTrue(plusRecent("v2", "1.9"))
        assertTrue(!plusRecent("1.0", "1.0"))
        assertTrue(!plusRecent("", "1.0"))
    }
}
