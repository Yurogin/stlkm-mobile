package fr.stlkm.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

// Les couleurs du hub PC : fond sombre, orange des deux tiroirs.
private val Fond = Color(0xFF15171C)
private val Carte = Color(0xFF23262E)
private val Texte = Color(0xFFF2F2F2)
private val Gris = Color(0xFF8A8F9E)
private val Orange = Color(0xFFFF8A29)
private val Encre = Color(0xFF1A1A1A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { Hub() }
    }
}

@Composable
private fun Hub() {
    val c = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(listOf<App>()) }
    var charge by remember { mutableStateOf(true) }
    var erreur by remember { mutableStateOf("") }
    var enCours by remember { mutableStateOf<String?>(null) }   // id en téléchargement
    var avance by remember { mutableStateOf(0f) }

    fun rafraichis(force: Boolean) {
        scope.launch {
            charge = true
            erreur = ""
            apps = Store.liste(c, force)
            if (apps.isEmpty()) erreur = "Rien à installer pour l'instant.\nGitHub limite à 60 demandes par heure : réessaie dans un moment."
            charge = false
        }
    }

    /** Télécharge puis tend l'APK à l'installateur : c'est lui qui demande confirmation. */
    fun installe(a: App) {
        if (enCours != null) return
        scope.launch {
            enCours = a.id
            avance = 0f
            val f = Install.telecharge(c, a) { avance = it }
            enCours = null
            if (f == null) erreur = "Téléchargement impossible : vérifie le réseau."
            else {
                Install.paquetDe(c, f)?.let { Store.retientPaquet(a.id, it) }
                Install.ouvreInstallateur(c, f)
            }
        }
    }

    LaunchedEffect(Unit) { rafraichis(false) }

    // Au retour de l'installateur, les versions installées ont pu changer.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) rafraichis(false) }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    // Le hub n'est pas une appli comme les autres dans sa propre liste : sa mise à jour
    // se range en haut, à côté d'Actualiser, plutôt qu'au milieu de ce qu'il propose.
    val estMoi = { a: App -> a.paquet == c.packageName || a.id == MOI }
    val moiMeme = apps.firstOrNull(estMoi)
    val autres = apps.filterNot(estMoi)

    Column(Modifier.fillMaxSize().background(Fond).safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("STLKM", color = Orange, fontSize = 32.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            if (moiMeme != null && plusRecent(moiMeme.version, BuildConfig.VERSION_NAME)) {
                Bouton(
                    if (enCours == moiMeme.id) "…" else "Mettre à jour " + moiMeme.version,
                    Orange, Encre, Modifier.padding(end = 8.dp),
                ) { installe(moiMeme) }
            }
            Bouton(if (charge) "…" else "Actualiser", Carte, Texte) { rafraichis(true) }
        }
        Text("Les applis du compte $COMPTE qui s'installent sur téléphone. · Hub " + BuildConfig.VERSION_NAME,
            color = Gris, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

        if (!Install.autorise(c)) {
            Colonne {
                Text("Android doit t'autoriser à installer depuis le hub.", color = Texte, fontSize = 14.sp)
                Bouton("Autoriser", Orange, Encre, Modifier.padding(top = 8.dp)) { Install.demandeAutorisation(c) }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (erreur.isNotEmpty()) Text(erreur, color = Gris, fontSize = 14.sp)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(autres, key = { it.id }) { a ->
                Colonne {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.nom, color = Texte, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            val sous = when {
                                a.aMettreAJour -> "installée " + a.installee + " · " + a.version + " disponible"
                                a.installee != null -> "installée · " + a.version
                                else -> a.version + " · " + (a.taille / 1024 / 1024).coerceAtLeast(1) + " Mo"
                            }
                            Text(sous, color = if (a.aMettreAJour) Orange else Gris, fontSize = 13.sp)
                        }
                        val libelle = when {
                            enCours == a.id -> "…"
                            a.aMettreAJour -> "Mettre à jour"
                            a.installee != null -> "Ouvrir"
                            else -> "Installer"
                        }
                        val vif = a.installee == null || a.aMettreAJour
                        Bouton(libelle, if (vif) Orange else Carte, if (vif) Encre else Texte) {
                            if (a.installee != null && !a.aMettreAJour) a.paquet?.let { Install.lance(c, it) }
                            else installe(a)
                        }
                    }
                    if (a.resume.isNotEmpty()) {
                        Text(a.resume, color = Gris, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (enCours == a.id) {
                        LinearProgressIndicator({ avance }, Modifier.fillMaxWidth().padding(top = 8.dp),
                            color = Orange, trackColor = Fond)
                    }
                }
            }
        }
    }
}

@Composable
private fun Colonne(contenu: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Carte).padding(12.dp), content = contenu)

@Composable
private fun Bouton(texte: String, fond: Color, encre: Color, modifier: Modifier = Modifier, clic: () -> Unit) =
    Text(texte, color = encre, fontWeight = FontWeight.Bold, fontSize = 15.sp,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(fond).clickable(onClick = clic)
            .padding(horizontal = 14.dp, vertical = 8.dp))
