#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# compare-benchmarks.sh
#
# Compare un run JMH courant à une baseline de référence sur un percentile
# de latence donné, et applique une politique de gate :
#   - amélioration ou stabilité   -> succès silencieux
#   - régression <= 20%           -> succès avec avertissement
#   - régression >  20%           -> échec (bloque le merge)
#
# Les deux fichiers (run courant et baseline) sont attendus au même format :
# la sortie JSON native de JMH (`java -jar benchmarks.jar -rf json -rff ...`).
# Promouvoir un run en nouvelle baseline revient donc simplement à :
#   cp jmh-result.json benchmarks/baseline.json
#
# Usage: ./compare-benchmarks.sh <current_jmh_result.json> <baseline.json>
# =============================================================================

CURRENT_RESULT_FILE="${1:?Usage: $0 <current_jmh_result.json> <baseline.json>}"
BASELINE_FILE="${2:?Usage: $0 <current_jmh_result.json> <baseline.json>}"

# Nom complet du benchmark à surveiller (champ "benchmark" dans la sortie
# JSON de JMH). À mettre à jour dès que le benchmark réel du matching
# engine remplace le placeholder, au Jalon 0.
readonly BENCHMARK_NAME="com.victorien.matchingengine.benchmark.PlaceholderBenchmark.run"

# Clé de percentile dans la sortie JMH (mode SampleTime). JMH ne reporte pas
# nativement le P99.995 visé par l'état de l'art : les paliers fixes
# disponibles sont 0, 50, 90, 95, 99, 99.9, 99.99, 99.999, 100. On retient
# ici 99.99 comme approximation la plus proche, en attendant l'introduction
# de HdrHistogram au Jalon 7, qui permettra un percentile arbitraire exact.
#
# IMPORTANT : le format exact de cette clé (ex. "99.99" vs "99.9900") doit
# être vérifié dans jmh-result.json après le premier run réel, et ajusté
# ici si nécessaire.
readonly PERCENTILE_KEY="99.99"

readonly REGRESSION_FAIL_THRESHOLD_PCT=20

# -----------------------------------------------------------------------------
# extract_percentile <file> <benchmark_name> <percentile_key>
#   Extrait la valeur de latence (ns/op) pour un percentile donné depuis
#   une sortie JSON JMH (tableau de résultats de benchmarks).
# -----------------------------------------------------------------------------
extract_percentile() {
    local file="$1"
    local benchmark_name="$2"
    local percentile_key="$3"

    jq -r --arg bench "$benchmark_name" --arg pct "$percentile_key" '
        .[] | select(.benchmark == $bench) | .primaryMetric.scorePercentiles[$pct]
    ' "$file"
}

# -----------------------------------------------------------------------------
# compute_regression_pct <baseline_value> <current_value>
#   Calcule le pourcentage de variation entre deux latences. Positif =
#   régression (plus lent que la référence), négatif = amélioration.
#   bash n'a pas d'arithmétique flottante native, on délègue donc à awk.
# -----------------------------------------------------------------------------
compute_regression_pct() {
    local baseline_value="$1"
    local current_value="$2"

    awk -v base="$baseline_value" -v curr="$current_value" '
        BEGIN {
            if (base == 0) { print "0"; exit }
            printf "%.2f", ((curr - base) / base) * 100
        }
    '
}

# -----------------------------------------------------------------------------
# write_summary <message>
#   Écrit dans le Job Summary de GitHub Actions, visible directement dans
#   l'interface du run sans permissions supplémentaires (contrairement à
#   un commentaire automatique sur la PR).
# -----------------------------------------------------------------------------
write_summary() {
    local message="$1"
    if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
        echo "$message" >> "$GITHUB_STEP_SUMMARY"
    else
        echo "$message"
    fi
}

main() {
    if [[ ! -f "$CURRENT_RESULT_FILE" ]]; then
        echo "Erreur : fichier de résultat introuvable : $CURRENT_RESULT_FILE" >&2
        exit 1
    fi

    local current_value
    current_value=$(extract_percentile "$CURRENT_RESULT_FILE" "$BENCHMARK_NAME" "$PERCENTILE_KEY")

    if [[ -z "$current_value" || "$current_value" == "null" ]]; then
        echo "Erreur : impossible d'extraire le percentile '$PERCENTILE_KEY' pour '$BENCHMARK_NAME'." >&2
        echo "Vérifie le nom du benchmark et le format des clés dans $CURRENT_RESULT_FILE." >&2
        exit 1
    fi

    # --- Cas bootstrap : aucune baseline n'existe encore ------------------
    if [[ ! -f "$BASELINE_FILE" ]]; then
        write_summary "## Métrologie — Aucune baseline trouvée"
        write_summary ""
        write_summary "Ce run constitue la référence initiale (${current_value} ns/op sur \`${BENCHMARK_NAME}\`, P${PERCENTILE_KEY})."
        write_summary "Aucune comparaison effectuée. Si tu valides ce jalon comme référence, committe ce résultat dans \`${BASELINE_FILE}\`."
        echo "Aucune baseline trouvée. Run de référence : ${current_value} ns/op."
        exit 0
    fi

    local baseline_value
    baseline_value=$(extract_percentile "$BASELINE_FILE" "$BENCHMARK_NAME" "$PERCENTILE_KEY")

    if [[ -z "$baseline_value" || "$baseline_value" == "null" ]]; then
        echo "Erreur : impossible d'extraire le percentile de référence depuis $BASELINE_FILE." >&2
        exit 1
    fi

    local regression_pct
    regression_pct=$(compute_regression_pct "$baseline_value" "$current_value")

    write_summary "## Métrologie — Comparaison à la baseline"
    write_summary ""
    write_summary "| Métrique | Baseline | Run courant | Variation |"
    write_summary "|---|---|---|---|"
    write_summary "| P${PERCENTILE_KEY} (\`${BENCHMARK_NAME}\`) | ${baseline_value} ns/op | ${current_value} ns/op | ${regression_pct}% |"
    write_summary ""

    local is_fail
    is_fail=$(awk -v r="$regression_pct" -v t="$REGRESSION_FAIL_THRESHOLD_PCT" 'BEGIN { print (r > t) ? "1" : "0" }')

    local is_regression
    is_regression=$(awk -v r="$regression_pct" 'BEGIN { print (r > 0) ? "1" : "0" }')

    if [[ "$is_fail" == "1" ]]; then
        write_summary "**Échec : régression de ${regression_pct}% > seuil de ${REGRESSION_FAIL_THRESHOLD_PCT}%.**"
        echo "ÉCHEC : régression de ${regression_pct}% (seuil : ${REGRESSION_FAIL_THRESHOLD_PCT}%)." >&2
        exit 1
    elif [[ "$is_regression" == "1" ]]; then
        write_summary "**Avertissement : régression de ${regression_pct}%, sous le seuil d'échec mais à surveiller.**"
        echo "AVERTISSEMENT : régression de ${regression_pct}%, sous le seuil d'échec."
        exit 0
    else
        write_summary "Pas de régression détectée (variation : ${regression_pct}%)."
        echo "OK : variation de ${regression_pct}% (amélioration ou stabilité)."
        exit 0
    fi
}

main "$@"
