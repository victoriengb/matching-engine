# Référence de benchmarks (`baseline.json`)

Ce dossier contient `baseline.json`, la référence de comparaison utilisée
par `scripts/compare-benchmarks.sh` pour détecter les régressions de
performance entre deux jalons du matching engine.

## Comment ça marche

- À chaque PR, push sur `main`, ou run manuel, le pipeline exécute les
  benchmarks JMH et compare le résultat à `baseline.json` (s'il existe).
- Une régression de plus de 20 % sur le percentile surveillé fait échouer
  le pipeline. Une régression plus faible déclenche un avertissement
  visible dans le Job Summary de GitHub Actions, sans bloquer le merge.
- Si `baseline.json` n'existe pas encore (cas du tout premier run), le
  run sert de référence initiale et le pipeline réussit sans comparaison.

## Comment promouvoir un run en nouvelle référence

Cette mise à jour est **toujours volontaire**, jamais automatique, pour
garantir que la référence corresponde à un jalon explicitement validé et
non à un commit intermédiaire quelconque.

Une fois qu'un jalon est validé :

1. Télécharge l'artefact `jmh-result` du run correspondant depuis l'onglet
   Actions de GitHub (ou récupère `jmh-result.json` localement après un
   `java -jar target/benchmarks.jar -rf json -rff jmh-result.json`).
2. Remplace le fichier de référence :

```bash
cp jmh-result.json benchmarks/baseline.json
git add benchmarks/baseline.json
git commit -m "Nouvelle baseline : Jalon X"
```

Le format de `baseline.json` est identique à celui de la sortie JMH
native (`-rf json`) : c'est un tableau de résultats de benchmarks, pas un
format personnalisé. Cela garantit que la même logique d'extraction
(`jq`) fonctionne indifféremment sur le run courant et sur la baseline.
