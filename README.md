# STLKM — le hub mobile

Le répertoire de tes applis Android, comme le hub STLKM l'est pour le PC.

**Tu n'as aucune liste à tenir.** Le hub lit ton compte GitHub : tout dépôt
public dont la dernière release contient un `.apk` apparaît dedans, avec un
bouton *Installer*. Quand tu publies une version, le bouton devient
*Mettre à jour*.

- Il reconnaît ce qui est déjà installé, même posé avant lui.
- Il télécharge, puis passe la main à l'installateur d'Android : **le hub
  n'installe jamais rien tout seul**, Android demande confirmation à chaque
  fois. La première fois, il faut l'autoriser à proposer des installations —
  un bouton le propose.
- Quand une release a plusieurs APK (téléphone et montre), il prend celui du
  téléphone.

GitHub n'accorde que 60 demandes par heure sans jeton. Le hub en fait une pour
lister les dépôts, puis ne va voir les releases que des vingt derniers poussés.
Un dépôt portant le sujet GitHub `stlkm-android` est toujours regardé, et
`stlkm-ignore` l'écarte — les mêmes sujets que sur PC.

Le catalogue commun (`Yurogin/stlkm-catalog`) donnera bientôt le nom affiché,
le résumé et le nom de paquet exact, comme il le fait pour le hub PC.

Pour le rebâtir : `gradlew assembleRelease`, l'APK sort dans
`app/build/outputs/apk/release/`.
