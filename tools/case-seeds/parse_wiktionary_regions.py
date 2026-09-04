#!/usr/bin/env python3
"""Regenerate {Lang}RegionTags.txt from a Kaikki wiktextract dump.

Emits `word;ES` / `word;LA` for words whose Wiktionary senses are labelled for one
regional bloc only. Words labelled for both blocs (or neither) are region-neutral and
omitted — BuildWordDbTask treats an absent entry as "no skew".

Source: Wiktionary via Kaikki wiktextract (CC BY-SA 4.0). No other corpus is consulted;
the 2026-07 curation's Leipzig sanity check is deliberately NOT reproduced (CC BY-NC).

Usage:
  parse_wiktionary_regions.py <kaikki.jsonl.gz> <WordsRaw.txt> <out.txt> [--report]

Download the dump from https://kaikki.org/dictionary/<Language>/
"""
import gzip
import json
import sys
import unicodedata
from collections import Counter

# Per-language label sets. Each entry maps the two skew codes emitted in the output file
# to the wiktextract sense `tags` that imply them. Codes are the language's own: Spanish
# uses ES/LA, English uses GB/US.
#
# Note for English: "Oxford" is deliberately absent from both sides — Oxford spelling is
# British but uses -ize, so it marks neither bloc cleanly.
ES_LABELS = {"Spain", "Canary-Islands", "Andalusia", "Castile", "Galicia",
             "Catalonia", "Basque-Country", "Aragon", "Asturias", "Murcia"}
GB_LABELS = {"UK", "British", "Britain", "England", "Commonwealth", "Ireland",
             "Scotland", "Wales", "Australia", "New-Zealand"}
US_LABELS = {"US", "American", "United-States", "Canada", "US-and-Canada"}
GB_RAW = {"in the uk", "british spelling", "chiefly british", "in britain"}
US_RAW = {"in the us", "american spelling", "chiefly american", "in america"}
LA_LABELS = {
    "Latin-America", "South-America", "Central-America", "Caribbean", "Rioplatense",
    "Mexico", "Argentina", "Chile", "Colombia", "Venezuela", "Peru", "Bolivia",
    "Ecuador", "Paraguay", "Uruguay", "Cuba", "Dominican-Republic", "Puerto-Rico",
    "Costa-Rica", "Guatemala", "Honduras", "Nicaragua", "Panama", "El-Salvador",
}
ES_RAW = {"in spain", "spain", "peninsular spanish", "castilian"}
LA_RAW = {"in latin america", "latin america", "in mexico", "in argentina",
          "in colombia", "in chile", "in bolivia", "in peru", "in venezuela"}

# Spanish only: senses in these registers never ground a regional tag — a universal
# word's regional SLANG sense (aceitar "to bribe", acabada, tío "dude") is not a
# lexical variant, and demoting the word for half the user base over it is exactly
# the only;GB failure shape. The sense still counts toward the share denominator.
SKIP_SENSE_TAGS = {
    "slang", "vulgar", "colloquial", "informal", "derogatory", "offensive",
    "humorous", "euphemistic", "familiar", "figuratively", "dated", "obsolete",
    "archaic", "historical", "rare",
}

# Spanish only: polysemy guards. A universal word with ONE genuinely-paired
# regional sense (gato "jack"<->mozo, control "remote"<->mando, esto, camión)
# must not sink for half the user base over that sense. Three structural rules:
# the pair-grounding sense must be the FIRST sense of its entry (a word whose
# identity is the regional meaning lists it first: computadora, zumo, celular's
# noun entry); a word that is a form/alternative of a DIFFERENT lemma is never
# tagged (bebe->beber, mas->más); and an absolute pooled-frequency cap backstops
# the rest (esto, cuál — no genuine variant pair sits that high). carro fails
# the first-sense rule only because "cart" is listed first; it is the marquee
# Tier-2 pair (SpanishRegion.kt names coche/carro), so it is curated back in.
FIRST_SENSE_ONLY = True
FREQ_CAP = 2500
CURATED = {"carro": "LA"}
# Structurally-clean but semantically wrong: Wiktionary labels a REGISTER
# difference as regional. rostro (formal "face") is literary everywhere —
# demoting it for Latin-American users is the only;GB failure shape.
CURATED_DENY = {"rostro"}

# A word is regional only if regional senses are a real share of its entry. Without
# this, common verbs with one obscure regional sense (abrir .09, acabar .12) get tagged
# and then sink for half the user base. True regionalisms sit well above: computadora
# 1.00, ordenador .50, patata .50, zumo .33.
MIN_REGIONAL_SHARE = 0.30

# A "British spelling of X" sense is decisive on its own, so it counts for more than a
# single ordinary regionally-labelled sense — otherwise colour/centre/realise fall under
# the share threshold on entries that also carry several neutral senses.
SPELLING_VARIANT_WEIGHT = 4

# One bloc must clearly dominate a word's regional evidence. A blunt "labelled for both
# blocs => neutral" rule loses the very pairs this file exists for: `centre` carries five
# British signals and one stray American sense, `center` twenty-eight American and one
# British. Neutral words (agua, alcanzar) sit near an even split and still drop out.
MIN_DOMINANCE = 0.67

# -ise/-ize is the one spelling family Wiktionary will not settle for us: it files both
# under "Oxford spelling", which is British but uses -ize, so neither tag names a bloc.
# The rule is mechanical apart from a closed set of verbs spelled -ise on BOTH sides of
# the Atlantic (they are not the Greek -ize suffix at all, so no -ize form exists).
ALWAYS_ISE = {
    "advertise", "advise", "apprise", "arise", "chastise", "circumcise", "comprise",
    "compromise", "demise", "despise", "devise", "disguise", "enterprise", "excise",
    "exercise", "franchise", "improvise", "incise", "merchandise", "premise", "revise",
    "supervise", "surmise", "surprise", "televise", "guise", "wise", "rise", "prise",
    "paradise", "promise", "expertise", "otherwise", "likewise", "precise", "concise",
}
ISE_SUFFIXES = ("ise", "ises", "ised", "ising", "isation", "isations")
IZE_SUFFIXES = ("ize", "izes", "ized", "izing", "ization", "izations")


def ise_ize_bloc(word, vocab):
    """GB for a British -ise spelling, US for its -ize twin, None when not this family.

    Only fires when the counterpart spelling is also in the vocabulary, so a word with no
    twin is never skewed against a user who has no alternative to reach for.
    """
    stem_exempt = any(word.startswith(x) or word == x for x in ALWAYS_ISE)
    if stem_exempt:
        return None
    for ise, ize in zip(ISE_SUFFIXES, IZE_SUFFIXES):
        if word.endswith(ise) and len(word) > len(ise) + 2:
            twin = word[: -len(ise)] + ize
            return "A" if twin in vocab else None
        if word.endswith(ize) and len(word) > len(ize) + 2:
            twin = word[: -len(ize)] + ise
            return "B" if twin in vocab else None
    return None


# Wiktionary sense categories that name a bloc directly (lowercased substring match).
CATEGORY_A = ("british english", "commonwealth english", "irish english", "australian english")
CATEGORY_B = ("american english", "canadian english", "us english")

# Gloss wording on alternative-spelling senses, where no tag names the bloc.
GLOSS_A = ("british", "commonwealth", "non-oxford")
GLOSS_B = ("american", "us spelling")

LABEL_SETS = {
    "es": ("ES", ES_LABELS, ES_RAW, "LA", LA_LABELS, LA_RAW),
    "en": ("GB", GB_LABELS, GB_RAW, "US", US_LABELS, US_RAW),
}


def norm(w):
    return unicodedata.normalize("NFC", w).strip().lower()


def main():
    dump, words_raw, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
    report = "--report" in sys.argv
    # --labels picks the language's skew codes and their Wiktionary labels (see LABEL_SETS).
    labels_key = "es"
    for i, arg in enumerate(sys.argv):
        if arg == "--labels" and i + 1 < len(sys.argv):
            labels_key = sys.argv[i + 1]
    lang_name = out_path.split("/")[-1].replace("RegionTags.txt", "")

    wanted = set()
    word_freq = {}
    for line in open(words_raw, encoding="utf-8"):
        if line.startswith("#") or ";" not in line:
            continue
        parts = line.split(";")
        w = norm(parts[0])
        wanted.add(w)
        if len(parts) > 1 and parts[1].strip().isdigit():
            word_freq[w] = int(parts[1])

    code_a, labels_a, raw_a, code_b, labels_b, raw_b = LABEL_SETS[labels_key]
    es, la, total = Counter(), Counter(), Counter()
    dec_a, dec_b = Counter(), Counter()  # decisive (relation-stating) evidence
    # Lexical-PAIR evidence (the "absolutamente;LA" incident, 2026-08-11): a
    # single-bloc sense only counts as pair-grounded when it NAMES a usable
    # counterpart — a single-word, in-vocabulary synonym the other bloc's user
    # reaches for instead (carro's Latin-America "car" sense lists coche/auto;
    # zumo's Spain sense lists jugo). A regional sense with no such counterpart
    # (absolutamente's "at all" -> only the multiword "en absoluto") is a sense
    # skew, not a lexical variant, and must not sink the word for half the
    # user base. Mirrors the EN spelling-pair filter, which had the transform
    # to lean on; Spanish pairs are lexical, so the synonym link is the pair.
    pair_syn = {}
    # Reciprocity: clean-register synonym lists of EVERY vocab word, so a pair
    # can be required to link BOTH ways (ordenador<->computadora, zumo<->jugo;
    # sobornar does NOT list aceitar back — the slang-sense skew drops out).
    syn_clean = {}
    # Words that are forms/alternatives of a different lemma (bebe->beber).
    other_lemma = set()
    scanned = 0
    with gzip.open(dump, "rt", encoding="utf-8") as f:
        for line in f:
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            scanned += 1
            w = norm(obj.get("word", ""))
            if not w or w not in wanted:
                continue
            for sense_idx, sense in enumerate(obj.get("senses", [])):
                total[w] += 1
                tags = set(sense.get("tags") or [])
                raws = {str(r).lower() for r in (sense.get("raw_tags") or [])}
                if labels_key == "es":
                    # A form of a DIFFERENT lemma is never tagged (bebe->beber:
                    # the verb form's users would eat the demotion). Plural-of
                    # is exempt — gafas/anteojos ARE the lexical items even
                    # though Wiktionary files them as plurals of gafa/anteojo.
                    if "plural" not in tags:
                        for rel in (sense.get("form_of") or []) + (sense.get("alt_of") or []):
                            target = norm(rel.get("word", "") if isinstance(rel, dict) else str(rel))
                            if target and target.split(" ")[0] != w:
                                other_lemma.add(w)
                    if tags & SKIP_SENSE_TAGS:
                        continue  # still in the share denominator, never evidence
                    for syn in (sense.get("synonyms") or []):
                        c = norm(syn.get("word", "") if isinstance(syn, dict) else str(syn))
                        if c and c != w and " " not in c:
                            syn_clean.setdefault(w, set()).add(c)
                hit_es = bool(tags & labels_a) or bool(raws & raw_a)
                hit_la = bool(tags & labels_b) or bool(raws & raw_b)
                # A spelling-variant sense ("British spelling of color") is the strongest
                # signal available: the headword itself belongs to that bloc, so count it
                # even though such senses often carry no other regional evidence.
                # "Alternative spelling of realize" often carries no bloc tag at all —
                # the only marker is the gloss text.
                if sense.get("alt_of") or sense.get("form_of"):
                    gloss = " ".join(sense.get("glosses") or []).lower()
                    if not hit_es and any(k in gloss for k in GLOSS_A):
                        hit_es = True
                    if not hit_la and any(k in gloss for k in GLOSS_B):
                        hit_la = True
                if (sense.get("alt_of") or sense.get("form_of")) and (hit_es or hit_la):
                    if hit_es:
                        es[w] += SPELLING_VARIANT_WEIGHT
                        dec_a[w] += 1
                    if hit_la:
                        la[w] += SPELLING_VARIANT_WEIGHT
                        dec_b[w] += 1
                    continue
                if hit_es:
                    es[w] += 1
                if hit_la:
                    la[w] += 1
                # Pair grounding: exactly one bloc on this sense, the sense
                # names a single-word in-vocab counterpart, and (polysemy
                # guard) the sense LEADS its entry — the regional meaning is
                # the word's identity, not a secondary sense.
                if hit_es != hit_la and (not FIRST_SENSE_ONLY or sense_idx == 0):
                    for syn in (sense.get("synonyms") or []):
                        c = norm(syn.get("word", "") if isinstance(syn, dict) else str(syn))
                        if c and c != w and " " not in c and c in wanted:
                            pair_syn.setdefault(w, set()).add(c)
                # Sense categories name the bloc outright ("British English") where the
                # sense tags often do not.
                for c in (sense.get("categories") or []):
                    nm = (c.get("name", "") if isinstance(c, dict) else str(c)).lower()
                    if any(k in nm for k in CATEGORY_A):
                        es[w] += SPELLING_VARIANT_WEIGHT
                        total[w] += SPELLING_VARIANT_WEIGHT
                        dec_a[w] += 1
                    elif any(k in nm for k in CATEGORY_B):
                        la[w] += SPELLING_VARIANT_WEIGHT
                        total[w] += SPELLING_VARIANT_WEIGHT
                        dec_b[w] += 1

            # Spelling pairs live in the entry-level `forms` list, not in sense tags:
            # `colour` lists ("color", [alternative, US]) and `color` lists
            # ("colour", [alternative, Commonwealth]). Each entry therefore names its
            # counterpart AND that counterpart's bloc — so credit the named form with the
            # bloc it carries, and the headword with the opposite one.
            for fm in (obj.get("forms") or []):
                if not isinstance(fm, dict):
                    continue
                ftags = set(fm.get("tags") or [])
                if "alternative" not in ftags:
                    continue
                fw = norm(fm.get("form", ""))
                in_a, in_b = bool(ftags & labels_a), bool(ftags & labels_b)
                if in_a == in_b:
                    continue  # unlabelled, or claimed by both — no signal
                if fw and fw in wanted:
                    (es if in_a else la)[fw] += SPELLING_VARIANT_WEIGHT
                    total[fw] += SPELLING_VARIANT_WEIGHT
                    (dec_a if in_a else dec_b)[fw] += 1
                if w in wanted:
                    (la if in_a else es)[w] += SPELLING_VARIANT_WEIGHT
                    total[w] += SPELLING_VARIANT_WEIGHT
                    (dec_b if in_a else dec_a)[w] += 1

    # The -ise/-ize family, resolved mechanically before the evidence-based pass so an
    # explicit Wiktionary label still wins where one exists.
    if labels_key == "en":
        for w in wanted:
            side = ise_ize_bloc(w, wanted)
            if side == "A":
                es[w] += SPELLING_VARIANT_WEIGHT
                total[w] += SPELLING_VARIANT_WEIGHT
                dec_a[w] += 1
            elif side == "B":
                la[w] += SPELLING_VARIANT_WEIGHT
                total[w] += SPELLING_VARIANT_WEIGHT
                dec_b[w] += 1

    tagged, weak, mixed, unpaired = [], [], [], []
    for w in sorted(set(es) | set(la)):
        # Verbs spelled -ise on both sides of the Atlantic are never a regional choice;
        # a stray regional sense must not sink them for half the users.
        if labels_key == "en" and any(w == x or w.startswith(x) for x in ALWAYS_ISE):
            continue
        regional = es[w] + la[w]
        if regional == 0:
            continue
        # Spanish: a tag needs a RECIPROCAL lexical pair — a clean single-bloc
        # sense of W naming counterpart C, and C naming W back from a clean
        # sense of its own. One-directional links are sense skews, not variants.
        # Then the polysemy backstops: never a form of another lemma, never
        # above the pooled-frequency cap.
        if labels_key == "es" and w in CURATED_DENY:
            continue
        if labels_key == "es" and w not in CURATED:
            partners = {
                c for c in pair_syn.get(w, set())
                if w in syn_clean.get(c, set())
            }
            if not partners or w in other_lemma or word_freq.get(w, 0) > FREQ_CAP:
                unpaired.append(w)
                continue
            pair_syn[w] = partners
        if labels_key == "en":
            # English carries decisive evidence — alternative-spelling forms, "British
            # English" categories, the -ise/-ize rule — heavily weighted, so the bloc with
            # the preponderance of it wins even when a stray sense label dissents
            # (`centre` collects five British signals against one American).
            if max(es[w], la[w]) / regional < MIN_DOMINANCE:
                mixed.append(w)
                continue
            winner = code_a if es[w] > la[w] else code_b
        else:
            # Spanish evidence is unweighted sense labels: a word claimed by both blocs is
            # genuinely ambiguous. Relaxing this wrongly made frijol and jícama Peninsular.
            if es[w] and la[w]:
                mixed.append(w)
                continue
            winner = code_a if es[w] else code_b
        share = regional / max(1, total[w])
        if share < MIN_REGIONAL_SHARE:
            weak.append(w)
            continue
        tagged.append((w, winner))

    meaning = {
        "es": "ES=Spain-preferred, LA=Latin-America-preferred",
        "en": "GB=British-preferred, US=American-preferred",
    }[labels_key]
    pair_note = (
        "# PAIR-GROUNDED (2026-08-11, the absolutamente;LA cleanup): a tag survives only\n"
        "# when a single-bloc sense names a single-word in-vocabulary counterpart synonym\n"
        "# (carro->coche, zumo->jugo) — a regional SENSE with no lexical alternative is\n"
        "# not a variant and must not sink the word for half the user base.\n"
        if labels_key == "es" else ""
    )
    header = (
        f"# {lang_name}RegionTags.txt — per-word regional skew. Derived from Wiktionary\n"
        "# regional sense labels, sense categories and alternative-spelling forms via Kaikki\n"
        "# wiktextract (CC BY-SA 4.0; attribution: Wiktionary contributors / Tatu Ylonen's\n"
        f"# wiktextract). {meaning}; words labelled for both blocs are omitted as neutral.\n"
        f"{pair_note}"
        "# Regenerate with tools/case-seeds/parse_wiktionary_regions.py --labels "
        f"{labels_key} — see docs/.plans/language-resources/plan.md. word;skew\n"
    )
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(header)
        for w, skew in tagged:
            f.write(f"{w};{skew}\n")

    n_a = sum(1 for _, s in tagged if s == code_a)
    print(f"scanned {scanned} entries; {len(wanted)} list words; "
          f"tagged {len(tagged)} ({n_a} {code_a} / {len(tagged) - n_a} {code_b})", file=sys.stderr)
    if report:
        print(f"omitted as neutral (no bloc dominates): {len(mixed)}", file=sys.stderr)
        print("  sample:", sorted(mixed)[:20], file=sys.stderr)
        print(f"omitted below {MIN_REGIONAL_SHARE:.0%} regional-sense share: {len(weak)}",
              file=sys.stderr)
        print("  sample:", sorted(weak)[:20], file=sys.stderr)
        if labels_key == "es":
            print(f"omitted as unpaired (no counterpart synonym): {len(unpaired)}",
                  file=sys.stderr)
            print("  sample:", sorted(unpaired)[:20], file=sys.stderr)
            print("kept, with counterparts:", file=sys.stderr)
            for w, skew in tagged:
                print(f"  {w};{skew}  <-> {','.join(sorted(pair_syn.get(w, set())))}",
                      file=sys.stderr)


if __name__ == "__main__":
    main()
