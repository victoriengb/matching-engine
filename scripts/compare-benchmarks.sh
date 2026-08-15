#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# compare-benchmarks.sh
#
# Compare un run JMH courant à une baseline de référence, sur PLUSIEURS
# benchmarks surveillés simultanément, et applique une politique de gate
# par benchmark :
#   - amélioration ou stabilité   -> succès silencieux pour ce benchmark
#   - régression <= 20%           -> avertissement pour ce benchmark
#   - régression >  20%           -> échec pour ce benchmark
#
# Politique d'échec globale : UN SEUL benchmark en échec suffit à faire
# échouer le pipeline. Une amélioration sur l'un ne compense jamais une
# régression bloquante sur l'autre -- chaque dimension surveillée (coeur
# de matching, transport inter-threads) doit être défendable
# indépendamment.
#
# Les fichiers de résultat courant et de baseline sont au même format :
# la sortie JSON native de JMH (`java -jar benchmarks.jar -rf json -rff ...`).
# Promouvoir un run en nouvelle baseline revient donc simplement à :
#   cp jmh-result.json benchmarks/baseline.json
#
# Usage: ./compare-benchmarks.sh <current_jmh_result.json> <baseline.json>
# =============================================================================

CURRENT_RESULT_FILE="${1:?Usage: $0 <current_jmh_result.json> <baseline.json>}"
BASELINE_FILE="${2:?Usage: $0 <current_jmh_result.json> <baseline.json>}"

readonly REGRESSION_FAIL_THRESHOLD_PCT=20

# Codes de statut par benchmark, utilisés comme codes de RETOUR de
# evaluate_benchmark() -- pas comme sortie textuelle -- afin d'éviter
# tout besoin de ré-exécuter la fonction pour en extraire le résultat.
readonly STATUS_OK=0
readonly STATUS_WARN=1
readonly STATUS_FAIL=2

# -----------------------------------------------------------------------------
# Benchmarks surveillés : nom complet JMH, clé de percentile JMH à
# surveiller pour ce benchmark. Chaque ligne est un couple "nom|percentile".
#
# ringBufferRoundTrip utilise 99.9 plutôt que 99.999 (retenu pour
# partialMatch) : à Level.Invocation sur un cycle mono-thread très
# court, la queue extrême du TransportLatencyBenchmark est davantage
# soumise au bruit de warmup JIT résiduel qu'à un signal exploitable --
# cf. limitation documentée dans la Javadoc de la classe (le busy-spin
# n'y attend jamais réellement). Le P99.9 reste un indicateur de queue
# tout en restant moins bruité qu'un palier plus extrême sur cette
# mesure spécifique.
# -----------------------------------------------------------------------------
readonly BENCHMARKS=(
    "com.victorien.matchingengine.benchmark.MatchingEngineBenchmark.partialMatch|99.999"
    "com.victorien.matchingengine.benchmark.TransportLatencyBenchmark.ringBufferRoundTrip|99.9"
)

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
#   Écrit dans le Job Summary de GitHub Actions.
# -----------------------------------------------------------------------------
write_summary() {
    local message="$1"
    if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
        echo "$message" >> "$GITHUB_STEP_SUMMARY"
    else
        echo "$message"
    fi
}

# -----------------------------------------------------------------------------
# evaluate_benchmark <benchmark_name> <percentile_key>
#   Évalue un seul benchmark : extraction, calcul de régression,
#   écriture dans le Job Summary et sur stderr (diagnostic humain).
#   Communique son verdict à l'appelant via le CODE DE RETOUR de la
#   fonction (STATUS_OK / STATUS_WARN / STATUS_FAIL), jamais via stdout
#   -- ce qui évite d'avoir à ré-exécuter la fonction pour distinguer
#   ses logs de son statut, piège de la version précédente de ce script.
# -----------------------------------------------------------------------------
evaluate_benchmark() {
    local benchmark_name="$1"
    local percentile_key="$2"

    local current_value
    current_value=$(extract_percentile "$CURRENT_RESULT_FILE" "$benchmark_name" "$percentile_key")

    if [[ -z "$current_value" || "$current_value" == "null" ]]; then
        echo "Erreur : impossible d'extraire le percentile '$percentile_key' pour '$benchmark_name'." >&2
        echo "Vérifie le nom du benchmark et le format des clés dans $CURRENT_RESULT_FILE." >&2
        return "$STATUS_FAIL"
    fi

    # --- Cas bootstrap : aucune baseline n'existe encore ---
    if [[ ! -f "$BASELINE_FILE" ]]; then
        write_summary "### \`${benchmark_name}\` -- Aucune baseline trouvée"
        write_summary "Ce run constitue la référence initiale (${current_value} ns/op, P${percentile_key})."
        write_summary "Aucune comparaison effectuée."
        write_summary ""
        return "$STATUS_OK"
    fi

    local baseline_value
    baseline_value=$(extract_percentile "$BASELINE_FILE" "$benchmark_name" "$percentile_key")

    if [[ -z "$baseline_value" || "$baseline_value" == "null" ]]; then
        # Benchmark absent de la baseline (ex. tout juste ajouté au
        # pipeline) : traité comme un bootstrap pour CE benchmark
        # précis, sans faire échouer le pipeline sur ce seul motif.
        write_summary "### \`${benchmark_name}\` -- Absent de la baseline"
        write_summary "Ce run constitue la référence initiale pour ce benchmark (${current_value} ns/op, P${percentile_key})."
        write_summary ""
        return "$STATUS_OK"
    fi

    local regression_pct
    regression_pct=$(compute_regression_pct "$baseline_value" "$current_value")

    write_summary "### \`${benchmark_name}\`"
    write_summary ""
    write_summary "| Métrique | Baseline | Run courant | Variation |"
    write_summary "|---|---|---|---|"
    write_summary "| P${percentile_key} | ${baseline_value} ns/op | ${current_value} ns/op | ${regression_pct}% |"
    write_summary ""

    local is_fail
    is_fail=$(awk -v r="$regression_pct" -v t="$REGRESSION_FAIL_THRESHOLD_PCT" 'BEGIN { print (r > t) ? "1" : "0" }')

    local is_regression
    is_regression=$(awk -v r="$regression_pct" 'BEGIN { print (r > 0) ? "1" : "0" }')

    if [[ "$is_fail" == "1" ]]; then
        write_summary "**Échec : régression de ${regression_pct}% > seuil de ${REGRESSION_FAIL_THRESHOLD_PCT}%.**"
        write_summary ""
        echo "ÉCHEC [${benchmark_name}] : régression de ${regression_pct}% (seuil : ${REGRESSION_FAIL_THRESHOLD_PCT}%)." >&2
        return "$STATUS_FAIL"
    elif [[ "$is_regression" == "1" ]]; then
        write_summary "**Avertissement : régression de ${regression_pct}%, sous le seuil d'échec mais à surveiller.**"
        write_summary ""
        echo "AVERTISSEMENT [${benchmark_name}] : régression de ${regression_pct}%, sous le seuil d'échec." >&2
        return "$STATUS_WARN"
    else
        write_summary "Pas de régression détectée (variation : ${regression_pct}%)."
        write_summary ""
        echo "OK [${benchmark_name}] : variation de ${regression_pct}% (amélioration ou stabilité)." >&2
        return "$STATUS_OK"
    fi
}

main() {
    if [[ ! -f "$CURRENT_RESULT_FILE" ]]; then
        echo "Erreur : fichier de résultat introuvable : $CURRENT_RESULT_FILE" >&2
        exit 1
    fi

    write_summary "## Métrologie -- Comparaison à la baseline"
    write_summary ""

    local overall_status="$STATUS_OK"

    for entry in "${BENCHMARKS[@]}"; do
        local benchmark_name="${entry%%|*}"
        local percentile_key="${entry##*|}"

        local benchmark_status="$STATUS_OK"
        # `|| benchmark_status=$?` capture le code de retour SANS
        # déclencher `set -e`, qui arrêterait sinon le script au premier
        # STATUS_FAIL/STATUS_WARN rencontré (tous deux non-nuls).
        evaluate_benchmark "$benchmark_name" "$percentile_key" || benchmark_status=$?

        if [[ "$benchmark_status" -gt "$overall_status" ]]; then
            overall_status="$benchmark_status"
        fi
    done

    case "$overall_status" in
        "$STATUS_FAIL")
            write_summary "## Résultat global : ÉCHEC"
            write_summary "Au moins un benchmark surveillé dépasse le seuil de régression."
            exit 1
            ;;
        "$STATUS_WARN")
            write_summary "## Résultat global : SUCCÈS AVEC AVERTISSEMENT"
            exit 0
            ;;
        *)
            write_summary "## Résultat global : SUCCÈS"
            exit 0
            ;;
    esac
}

main "$@"