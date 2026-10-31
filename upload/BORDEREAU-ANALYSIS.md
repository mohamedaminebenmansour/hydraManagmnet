# BORDEREAU-ANALYSIS.md — Water-works bordereaux, analysed for HYDRA

Prepared 31 October 2026 from 9 public bordereaux des prix / DQE / métrés.
Purpose: give HYDRA (and the AI writing its code) a reliable picture of how
water-works bordereaux are structured, so the data model, the Excel import and
the "ouvrage" assignment match reality.

Every finding below cites the file it comes from (F1…F9).

---

## 1. Sources

| ID | File | Country / year | Type | What it covers | Prices |
|----|------|----------------|------|----------------|--------|
| F1 | JICA – Béja, bordereau des prix (openjicareport 11645512_01) | Tunisia, 2001 | Bordereau (BPU), 133 lines | PEHD pipes, cast-iron valves, earthworks, pipe laying, standard structures, civil works of small reservoirs | blank |
| F2 | JICA – Mahdia, bordereau / devis estimatif (11646403) | Tunisia, 2001 | Bordereau + DQE, 2 projects | El Aitha El Bkour (pipes 15 km) and Bouslim (pipes 63 km, réservoir sur piliers 250 m³, station de reprise, réservoir semi-enterré 150 m³) | blank, quantities filled |
| F3 | JICA – Kairouan Chelalga, bordereau / devis estimatif (11645934) | Tunisia, 2001 | Bordereau + DQE | Pipes 25 km, 2 brise-charge 8 m³ | blank, quantities filled |
| F4 | JICA – métré, réservoir 60 m³ + station de pompage (11646445_03) | Tunisia (mentions SONEDE), 2001 | Métré | Pipes 17 km, réservoir 60 m³, existing pumping station + local GIC, hydromechanical equipment, chlorination | quantities only |
| F5 | JICA – Final report ch. 7, cost model table 7.1.1 (11644788_02) | Tunisia, 2001 | Estimate template | Pipes, laying, réservoir 100 m³ + 2 brise-charge 8 m³, pumping station, pump Q 8 m³/h HMT 150 m, electrical | blank |
| F6 | Réservoir Taous – DAO / CPS (tanmia.ma) | Morocco, 2026 | Bordereau, 39 lines | Mixed: réservoir 35 m³ + PEHD conduites + solar pumping + well deepening | blank |
| F7 | Réservoir 48 m³ AFDRIK – DQE (tanmia.ma) | Morocco, 2025–26 | DQE, 9 lines | Small reinforced-concrete réservoir | blank, quantities filled |
| F8 | FAMSI – 5 solar water points, DQE + BPU (transparenciafamsi) | Senegal | DQE | Manual boreholes, solar pumping, resin tanks on support, network, bornes fontaines, chlorination | blank, quantities filled |
| F9 | Landrévarzec – DQE/BPU (amf29.asso.fr) | France, 2015 | DQE/BPU, 20 lines | PVC/PEHD pipe works under and along a road, branchements | blank, quantities filled |

Limits: the Tunisian files are 25 years old and have no prices. Use them for
structure, séries and article designations, never for costs. Confirm against a
recent SONEDE / CRDA DCE (TUNEPS) and the company's own bordereaux.

---

## 2. How the bordereaux are organised

### 2.1 The two axes every bordereau mixes

Each article belongs to two things at once:

1. **A nature of work**: fourniture (supply), pose et essai (laying and testing),
   terrassement, génie civil, équipement hydromécanique / électrique, essais,
   installation de chantier, divers.
2. **An ouvrage**: a conduite, a réservoir, a station de pompage, a brise-charge…

Files choose one axis for their sections and leave the other implicit:

| Pattern | Sections are… | Files | Consequence for HYDRA |
|---------|---------------|-------|-----------------------|
| A. By nature of work, numbered séries | Série 100 fourniture PEHD, 150 robinetterie fonte, 200 terrassement, 210 pose, 220 ouvrages courants, 300 génie civil (sous-séries 310, 320, 340) | F1 | The ouvrage must be found line by line (keywords, DN, "réservoir"). Civil works of several réservoirs share the same lines. |
| B. By nature of work, then one sub-section per ouvrage for civil works | I Fourniture · II Pose et essai · III Terrassement · IV Génie civil → 41 ouvrages de petite hydraulique, 42 réservoir sur piliers 250 m³, 43 station de reprise, 44 réservoir semi-enterré 150 m³ | F2, F4 (2.1 … 2.8, with 2.6 réservoir 60 m³, 2.7 station, 2.8 équipement hydromécanique), F5 (I fourniture · II réseaux · III-1 réservoir + brise-charge · III-2 station · IV hydromécanique et électrique) | Pipe sections go to the conduite; each civil-works sub-section maps to exactly one ouvrage. |
| C. By contract package (sous-lot) | Sous-lot I fourniture · Sous-lot II terrassement + ouvrages courants + pose · Sous-lot III construction de 2 brise-charge | F3 | Sous-lot III = one ouvrage type (×2). Sous-lots I and II = conduite. |
| D. By ouvrage | Série I Réservoir · II Conduites · III Pompage solaire · IV Puits | F6, and F8 (sections 2–8: générateur PV, pompage, réseau production, réseau distribution, borne fontaine, réservoir, traitement) | Each section maps directly to one ouvrage. |
| E. Flat list | 9 or 20 numbered lines, no séries | F7, F9 | Whole file = one ouvrage (F7) or one conduite + common items (F9). |

**Design rule for HYDRA:** store the section path exactly as written (lot /
sous-lot / série / sous-série), and *separately* store `ouvrage_id` and
`work_nature`. Never derive one from the other.

### 2.2 Supply and laying are separate lines for the same pipe

In F2 and F3 the same pipe appears twice with the same length:
"Conduite PEHD PN10 DE 110 — fourniture — 4 297 ml" (F2, I.2) and
"Pose et essai conduite PEHD DE 110 — 4 297 ml" (F2, II.2). F3 does the same
between sous-lot I and sous-lot II (supply 5 320 ml, laying 5 067.1 ml for DE 160,
so laying is about 95 % of supply: an allowance for waste).

Consequence:
- In attachements, supply can be paid on delivery to site and laying on
  progress. HYDRA must track both quantities for the same pipe.
- Add `paired_line_id` on `bordereau_line` to link a fourniture line to its
  pose line. The import can suggest pairs when designation material + DE + PN
  match.

### 2.3 Article numbering is free text

Numbers found: `101a`, `105i`, `343b` (F1) · `A2.13`, reused twice (F3) ·
`1.2.7.4`, `6.7.16.3`, and an out-of-place `6.8.15` (F4) · `42-14` style
(sub-series 42, line 14, F2) · `Prix 5` restarting at 1 in each série (F6) ·
`06a`, `06b` (F9).

Consequence: `article_no` is `text`, not unique on its own. Uniqueness is
(`version_id`, `section_path`, `article_no`). Keep a `sort_order` integer for
display order.

### 2.4 Units must be normalised

| Normalised | Variants seen | Files |
|------------|---------------|-------|
| `ML` (mètre linéaire) | ml, ML, Ml, mètre linéaire, M (F8) | all |
| `M3` | m³, m3, M3, mètre cube | all |
| `M2` | m², m2, M2, M² | all |
| `U` | U, u, unité, U. | all |
| `KG` | kg, KG, Kg, kilogramme | F1, F2, F3, F6, F7 |
| `ENS` | Ens, ENS, Ens., ensemble | F2, F4, F9 |
| `FORFAIT` | forfait, Forf, FF | F1, F2, F6, F8 |
| `LOT` | lots | F5 |

Keep `unit_raw` as written and `unit` normalised. Flag, don't fix, suspicious
units: F7 line 1 "F" for excavation, F7 line 7 "U" for 137.92 of plaster
(should be m²), F8 pipes counted in "unité" in the DQE but per metre in the BPU.

### 2.5 Concrete names are not comparable between files

"B3" means 350 kg/m³ in F1 (B1 150, B2 250, B3 350) but 250 kg/m³ for
foundations in F2 (B3 250, B5 350). F3, F4, F6, F7 write the dosage directly
(350, 400 kg/m³). **Store the dosage in kg/m³ as a characteristic; never key
on the label "B3".**

---

## 3. Séries found and the ouvrage type they belong to

"Files" = files containing a section or a clear group of lines of that kind.

| Normalised série / section | Typical labels in the files | Files | Ouvrage type |
|----------------------------|-----------------------------|-------|--------------|
| Installation et repli de chantier | Installation, amenée et repli; mesures de sécurité; plans d'exécution | F8, F9 | COMMUN |
| Fourniture conduites et pièces PEHD | Série 100; I Fourniture; Sous-lot I A1–A2; 2.1 | F1, F2, F3, F4, F5 | CONDUITE |
| Fourniture robinetterie et pièces fonte / acier | Série 150; A2.2 Vannes, A2.13 joints, A2.16 compteur | F1, F2, F3, F4, F5 | CONDUITE (or the ouvrage where installed) |
| Terrassement pour conduites | Série 200; III Terrassement (tranchée 60 cm); Sous-lot II A1; 2.2 | F1, F2, F3, F4, F5, F6 | CONDUITE |
| Pose et essai de conduites | Série 210; II Pose et essai; Sous-lot II B; 2.3 | F1, F2, F3, F4, F5 | CONDUITE |
| Ouvrages courants sur conduite | Série 220; 41 Ouvrages de petite hydraulique; Sous-lot II A2; 2.4 | F1, F2, F3, F4, F6 | OUVRAGE_ANNEXE (or CONDUITE) |
| Ouvrages de distribution | Borne fontaine, potence, branchements, traversées; 2.5 | F1, F2, F3, F4, F5, F8 | BRANCHEMENTS |
| Génie civil réservoir | Série 300 (310, 320, 340); 42; 44; 2.6; III-1; Série I Réservoir; F7 whole file; F8 section 7 | F1, F2, F4, F5, F6, F7, F8 | RESERVOIR |
| Génie civil brise-charge / bâche de reprise | Sous-lot III; III-1 (2 × 8 m³) | F3, F5 | RESERVOIR (subtype BRISE_CHARGE) |
| Génie civil station de pompage / reprise, local | 43 Station de reprise + abri; 2.7 local GIC; III-2 | F2, F4, F5 | STATION_POMPAGE |
| Équipement hydromécanique station | 2.8; IV-1 équipement de pompe | F4, F5 | STATION_POMPAGE |
| Équipement électrique / énergie | IV-3 tableau, câbles, transformateur 25 kVA; Série III pompage solaire; section 2 générateur PV | F5, F6, F8 | STATION_POMPAGE |
| Traitement / chloration | Chambre de chloration (2.7–2.8); IV-2 javellisation; section 8 chlorateur | F4, F5, F8 | STATION_POMPAGE (or RESERVOIR) |
| Forage / puits | Forage manuel A1–A10; tête de forage; puits traditionnel | F4, F6, F8 | FORAGE |
| Travaux sous voirie | Forage horizontal sous route; réfection chaussée; traversées | F1, F2, F4, F9 | CONDUITE |
| Essais | Épreuves d'étanchéité; essais de débit | F8, F9 (and "pose et essai" lines everywhere) | CONDUITE / FORAGE |

---

## 4. Recurring articles (normalised)

Designations shortened and normalised; `{}` = a value that varies (becomes a
characteristic). "Nature" = work_nature.

| # | Normalised designation | Unit | Ouvrage type | Nature | Identifying keywords | Files |
|---|------------------------|------|--------------|--------|----------------------|-------|
| 1 | Conduite PEHD DE {de} PN {pn} (fourniture) | ML | CONDUITE | FOURNITURE | PEHD, PEhd, DE, PN | F1–F6, F8, F9 |
| 2 | Pose et essai conduite PEHD DE {de} | ML | CONDUITE | POSE | pose, essai, DE | F1–F5 |
| 3 | Fouille / déblai en tranchée | M3 | CONDUITE | TERRASSEMENT | fouille, déblai, tranchée, rigole | F1–F6 |
| 4 | Remblai de tranchée (compacté) | M3 | CONDUITE | TERRASSEMENT | remblai, remblaiement | F1–F6 |
| 5 | Lit de pose en sable (10 cm) | M3 | CONDUITE | TERRASSEMENT | sable, lit de pose | F1–F6 |
| 6 | Plus-value terrain rocheux | M3 | CONDUITE | TERRASSEMENT | rocheux, rocher, plus-value | F4, F6 |
| 7 | Robinet-vanne fonte DN {dn} PN {pn} | U | CONDUITE | FOURNITURE | robinet vanne, vanne, opercule | F1–F6, F9 |
| 8 | Ventouse simple / double effet DN {dn} | U | CONDUITE | FOURNITURE | ventouse | F1–F5 |
| 9 | Joint de démontage DN {dn} | U | CONDUITE | FOURNITURE | joint de démontage | F1–F4 |
| 10 | Manchette bridée fonte DN {dn} L {l} | U | CONDUITE | FOURNITURE | manchette | F1–F4 |
| 11 | Té PEHD / Té fonte bridé {dn1}/{dn2} | U | CONDUITE | FOURNITURE | té | F1–F5 |
| 12 | Réduction / cône {dn1}/{dn2} | U | CONDUITE | FOURNITURE | réduction, cône | F1–F5 |
| 13 | Coude PEHD 90° / 45° / 30° DE {de} | U | CONDUITE | FOURNITURE | coude | F1–F4 |
| 14 | Collet bridé PEHD DE {de} / DN {dn} | U | CONDUITE | FOURNITURE | collet, raccord avec bride | F1–F4 |
| 15 | Compteur volumétrique DN {dn} | U | CONDUITE or STATION | FOURNITURE | compteur | F1–F5 |
| 16 | Clapet anti-retour DN {dn} | U | CONDUITE or STATION | FOURNITURE | clapet | F1, F2, F4 |
| 17 | Collier de prise en charge DE {de}/{de2} | U | BRANCHEMENTS | FOURNITURE | collier de prise | F1, F3 |
| 18 | Ouvrage / regard de ventouse (point haut) | U | OUVRAGE_ANNEXE | GENIE_CIVIL | regard de ventouse, point haut | F1–F4, F6 |
| 19 | Ouvrage / regard de vidange (point bas) | U | OUVRAGE_ANNEXE | GENIE_CIVIL | vidange, point bas | F1–F4, F6 |
| 20 | Ouvrage de sectionnement (type or dimensions) | U | OUVRAGE_ANNEXE | GENIE_CIVIL | sectionnement | F1–F4, F6 |
| 21 | Ouvrage de piquage | U | OUVRAGE_ANNEXE | GENIE_CIVIL | piquage | F1, F2 |
| 22 | Borne fontaine | U | BRANCHEMENTS | GENIE_CIVIL | borne fontaine, BF | F1–F5, F8 |
| 23 | Potence | U | BRANCHEMENTS | GENIE_CIVIL | potence | F2, F4, F5 |
| 24 | Branchement public / particulier | U | BRANCHEMENTS | POSE | branchement | F1–F4, F9 |
| 25 | Traversée de route / piste | ML | CONDUITE | GENIE_CIVIL | traversée, passage busé, forage horizontal | F2, F4, F9 |
| 26 | Grillage avertisseur | ML | CONDUITE | FOURNITURE | grillage avertisseur | F2 |
| 27 | Raccordement sur conduite existante (SONEDE / GR) | FORFAIT | CONDUITE | POSE | raccordement, existante, SONEDE | F2, F5, F9 |
| 28 | Fouille en pleine masse pour ouvrage | M3 | RESERVOIR / STATION | TERRASSEMENT | pleine masse | F1–F4, F6, F7 |
| 29 | Béton de propreté (≈150 kg/m³) | M3 or M2 | RESERVOIR / STATION / ANNEXE | GENIE_CIVIL | propreté | F1–F7 |
| 30 | Béton armé dosé {dosage} kg/m³ | M3 | RESERVOIR / STATION / ANNEXE | GENIE_CIVIL | béton armé, dosé, hydrofuge | F1–F7 |
| 31 | Acier haute adhérence (HA) | KG | RESERVOIR / STATION / ANNEXE | GENIE_CIVIL | acier HA, haute adhérence | F1–F3, F6, F7 |
| 32 | Enduit étanche / cuvelage / hydrofuge | M2 | RESERVOIR | GENIE_CIVIL | étanche, cuvelage, hydrofuge | F1–F4, F6 |
| 33 | Enduit ordinaire | M2 | RESERVOIR / STATION | GENIE_CIVIL | enduit | F2–F4, F6, F7 |
| 34 | Badigeon Flintkote / surfacer / chaux | M2 | RESERVOIR / STATION | GENIE_CIVIL | badigeon, Flintkote, surfa | F1–F4 |
| 35 | Joint d'étanchéité (reprise de bétonnage) | ML | RESERVOIR | GENIE_CIVIL | joint d'étanchéité | F1, F2, F4 |
| 36 | Trappe d'entrée (tôle) | U | RESERVOIR | EQUIPEMENT | trappe | F1, F2, F4, F6, F7 |
| 37 | Échelle (alu / acier galvanisé) | U | RESERVOIR | EQUIPEMENT | échelle | F1, F2, F4, F6 |
| 38 | Porte ventilée / châssis d'aération | U | RESERVOIR / STATION | EQUIPEMENT | porte ventilée, aération | F1–F4 |
| 39 | Robinet à flotteur DN {dn} | U | RESERVOIR | EQUIPEMENT | flotteur | F1–F5 |
| 40 | Crépine bronze DN {dn} | U | RESERVOIR / STATION | EQUIPEMENT | crépine | F1–F5 |
| 41 | Lignes arrivée / départ / trop-plein / vidange DN {dn} | U | RESERVOIR | EQUIPEMENT | ligne d'arrivée, départ, trop-plein | F1, F4 |
| 42 | Étanchéité de toiture multicouche | M2 | RESERVOIR / STATION | GENIE_CIVIL | étanchéité, toiture | F1, F2, F4 |
| 43 | Clôture + portail | ML or U | RESERVOIR / STATION | DIVERS | clôture, portail | F1, F2, F6 |
| 44 | Pompe immergée / électrique Q {q} m³/h HMT {hmt} m, {kw} kW | U | STATION_POMPAGE | EQUIPEMENT | pompe | F5, F6, F8 |
| 45 | Tête de forage / colonne montante DN {dn} | U | FORAGE | EQUIPEMENT | tête de forage, colonne | F4, F8 |
| 46 | Chloration: pompe doseuse / chlorateur | U | STATION_POMPAGE | EQUIPEMENT | chlor, javellisation | F4, F5, F8 |
| 47 | Équipement électrique: tableau, câbles, transformateur / solaire, variateur | U, ML | STATION_POMPAGE | EQUIPEMENT | câble, tableau, transformateur, panneaux, variateur | F5, F6, F8 |
| 48 | Installation et repli de chantier | ENS / FORFAIT | COMMUN | INSTALLATION | installation, repli, amenée | F8, F9 |

---

## 5. Characteristics each ouvrage type needs

Values seen in the files are given as evidence for the allowed values.

### CONDUITE
| Key | Type | Values seen |
|-----|------|-------------|
| `usage` | enum | ADDUCTION, REFOULEMENT, DISTRIBUTION (F8 separates "réseau production" and "réseau distribution") |
| `materiau` | enum | PEHD (dominant), FONTE (fittings), PVC (F9), ACIER_GALVANISE (F6, F8), AC (existing pipes, F9) |
| `de_mm` (PEHD/PVC outside diameter) | integer | 25, 32, 63, 75, 90, 110, 125, 160, 200, 250 |
| `dn_mm` (fonte/acier nominal diameter) | integer | 40, 50, 60, 65, 80, 100, 150, 200, 250, 300 |
| `pn_bar` | enum | 10, 16 |
| `assemblage` | enum | ELECTROSOUDABLE (F3), BOUT_A_BOUT, BRIDE, JOINT_CAOUTCHOUC (F6, F9) |
| `longueur_m` | number | e.g. 1 404 to 21 915 per diameter (F2) |
| `pk_debut`, `pk_fin` | number | not in the files; needed for site progress |
| `largeur_tranchee_m` | number | 0.60 (F2), up to 0.70 (F3) |

A conduite ouvrage can hold several diameters (F2 Bouslim: DE 250 to DE 90 on
one network). Either one ouvrage per diameter-run, or one ouvrage "Réseau X"
with a list of `troncons` [{de_mm, pn_bar, longueur_m}]. Recommended: the list,
because the bordereau lines are per diameter but the site thinks per network.

### RESERVOIR
| Key | Type | Values seen |
|-----|------|-------------|
| `sous_type` | enum | SEMI_ENTERRE (F2 150 m³, F5 20 m³), SURELEVE / SUR_PILIERS (F2 250 m³), BRISE_CHARGE (F3 8 m³, F5 8 m³), BACHE_REPRISE (F5), CUVE_SUR_SUPPORT (F8 resin 5–8 m³ on 5 m support) |
| `capacite_m3` | number | 5, 6, 8, 10, 20, 35, 48, 60, 100, 150, 250 |
| `materiau` | enum | BETON_ARME, RESINE (F8), MACONNERIE |
| `dosage_beton_kg_m3` | integer | 350, 400 |
| `nb_cuves` | integer | not stated; default 1 |
| `revetement_interieur` | enum | ENDUIT_ETANCHE, CUVELAGE, CARRELAGE (F6, F7) |
| `dn_arrivee`, `dn_depart`, `dn_trop_plein`, `dn_vidange` | integer | 60–200 (F1 343–346, F4 6.7) |
| `cloture` | boolean | F1, F2, F6 |

### STATION_POMPAGE
| Key | Type | Values seen |
|-----|------|-------------|
| `sous_type` | enum | STATION_REPRISE (F2), POMPAGE_FORAGE (F4, F6, F8) |
| `debit_m3h` | number | 4 (F8), 8 (F5) |
| `hmt_m` | number | 40 (F8), 150 (F5) |
| `nb_pompes` | integer | 1 per site in the files |
| `puissance_kw` | number | 5.5 (F6 7.5 HP) |
| `energie` | enum | RESEAU_ELECTRIQUE (F5, transformateur 25 kVA), SOLAIRE (F6 585 Wc panels + variateur, F8 230 Wc) |
| `chloration` | boolean | F4, F5, F8 |
| `local_technique` | boolean | F2 abri, F4 local GIC, F8 |

### FORAGE
| Key | Type | Values seen |
|-----|------|-------------|
| `type` | enum | FORAGE_MECANIQUE, FORAGE_MANUEL (F8), PUITS (F6) |
| `profondeur_m` | number | not stated |
| `diametre_tubage_mm` | number | DN 65 colonne (F4) |
| `materiau_tubage` | enum | PVC (F8), ACIER (F4 implied) |
| `debit_exploitation_m3h` | number | from essais de débit (F8) |

### OUVRAGE_ANNEXE (on a conduite)
| Key | Type | Values seen |
|-----|------|-------------|
| `type` | enum | VENTOUSE, VIDANGE, SECTIONNEMENT, PIQUAGE, REDUCTEUR_PRESSION, BRISE_CHARGE_EN_LIGNE |
| `modele` | text | "type P1", "P2.1", "P4" (F4), "type I / II" (F1) |
| `dimensions` | text | "1.2/1.5", "0.9×0.9" (F2, F3) |
| `pk` | number | position on the conduite |

These are counted in units (dozens per project: F2 Bouslim has 56 ventouse
regards and 48 vidange regards). Do **not** create one HYDRA ouvrage per regard.
Treat them as quantities inside the CONDUITE (or one OUVRAGE_ANNEXE per type),
and record PK positions only if the site wants them.

### BRANCHEMENTS
| Key | Type | Values seen |
|-----|------|-------------|
| `nb_bornes_fontaines` | integer | 5–42 |
| `nb_potences` | integer | 3–4 |
| `nb_branchements_publics` | integer | 5 (F4: école, dispensaire, mosquée) |
| `nb_branchements_particuliers` | integer | 3–10 |

### COMMUN
Installation et repli, plans d'exécution, essais globaux, raccordement au
réseau existant, sécurité. No characteristics.

---

## 6. Inconsistencies found (what the import must tolerate)

| File | Problem |
|------|---------|
| F1 | Line 107f "Réduction DE 110/DE 750": typo for 75. Lines 155, 161, 162, 165 have no unit in the form. |
| F2 | Two lines numbered 5 in El Aitha El Bkour; DQE units "ENS" / "Forf" differ from the bordereau "U". "Plancher corps creux" priced in kg in one column and m² in the other. |
| F3 | A2.12 missing; A2.13 and A2.17 reused in sous-lot II; several items all numbered "1" or "11" inside one group. |
| F4 | 1.2.11.3 and 1.2.11.4 identical; line "6.8.15" inside série 6.7; section 2.8 has a "Nombre" column but no unit. |
| F5 | Template: header rows have no unit (only leaf lines are priced). |
| F6 | Série III prix 13 titled "siège" but describes a fence; Série IV prix numbered 1 but referenced as prix 14. |
| F7 | Unit "F" for excavation; plaster quantity 137.92 in "U". |
| F8 | Line 7.3 missing; grand total (1–7) excludes section 8; DQE and BPU units differ for the same item. |
| F9 | Line 16 has no unit and no quantity. |

Import rules derived from this:
1. Never reject a file for these problems. Import, then show warnings per line.
2. A line with a designation but no unit and no quantity = a section title.
3. A line with a unit but no quantity = keep it with quantity 0 and a warning.
4. Duplicate `article_no` inside the same section = keep both, warn.
5. Unknown unit = keep `unit_raw`, set `unit` null, warn.

---

## 7. What this means for the HYDRA data model (for prompts 3, 5 and 6)

### 7.1 `ouvrage`
- `type` enum: CONDUITE, RESERVOIR, STATION_POMPAGE, FORAGE, OUVRAGE_ANNEXE,
  BRANCHEMENTS, ASSAINISSEMENT, COMMUN, AUTRE. (COMMUN is created automatically
  for every chantier.)
- `characteristics` jsonb validated against section 5 (allowed keys and enums
  per type). CONDUITE carries a `troncons` array.

### 7.2 `bordereau_line`
| Column | Why |
|--------|-----|
| `section_path` text[] | e.g. `{"Sous-lot II","B","B2.11"}` or `{"IV Génie civil","42 Réservoir sur piliers 250 m³"}` (2.1) |
| `article_no` text | free format (2.3) |
| `sort_order` int | display order |
| `designation` text | as written |
| `unit_raw` text, `unit` enum | normalisation (2.4) |
| `quantity` numeric(18,3), `unit_price` numeric(18,3) | |
| `ouvrage_id` uuid null | assignment |
| `work_nature` enum | FOURNITURE, POSE, TERRASSEMENT, GENIE_CIVIL, EQUIPEMENT, ESSAIS, INSTALLATION, DIVERS (2.1) |
| `paired_line_id` uuid null | fourniture ↔ pose of the same pipe (2.2) |
| `specs` jsonb | extracted values: `de_mm`, `dn_mm`, `pn_bar`, `materiau`, `dosage_kg_m3`, `capacite_m3`, `longueur_m` |
| `import_warnings` text[] | (section 6) |

### 7.3 Extraction patterns for `specs` (case-insensitive)
- `DE\s*(\d{2,3})` → de_mm · `DN\s*(\d{2,4})` → dn_mm · `PN\s*(\d{2})` → pn_bar
- `(\d+(?:[.,]\d+)?)\s*m(?:3|³)` in a section or designation with "réservoir",
  "brise", "bâche" → capacite_m3
- `dos[ée]\s*(?:à\s*)?(\d{3})\s*kg` → dosage_kg_m3
- material keywords: PEHD|PEhd → PEHD; fonte → FONTE; PVC → PVC; acier
  galvanisé|galva → ACIER_GALVANISE

### 7.4 Ouvrage suggestion during import (in this order)
1. Section label contains "réservoir", "brise charge", "bâche" → that RESERVOIR
   (match capacity if the chantier has several).
2. Section label contains "station", "pompage", "reprise", "local GIC",
   "hydromécanique", "solaire" → STATION_POMPAGE.
3. Section label contains "forage", "puits" → FORAGE.
4. "Installation", "repli", "plans d'exécution" → COMMUN.
5. Line or section with conduite / pose / tranchée / PEHD / DE → CONDUITE
   (if one conduite; otherwise match by DE against `troncons`).
6. Borne fontaine, potence, branchement → BRANCHEMENTS.
7. Regard / ouvrage de ventouse, vidange, sectionnement, piquage → the CONDUITE
   (as quantities) unless the chantier has an OUVRAGE_ANNEXE.
8. Otherwise leave empty for the user.

### 7.5 Taxes
F2 shows "TVA (18 %)" — the rate in force in 2001. Tax rates must be
configuration with effective dates, never constants in code.

---

## 8. Open questions for the company

1. Does the company receive bordereaux as Excel or only PDF? (Decides whether
   the import must handle PDF tables.)
2. In attachements, are supply and laying of pipes paid separately, as the
   bordereaux suggest?
3. Should regards (ventouse, vidange, sectionnement) be tracked individually
   with PK on site, or only as quantities?
4. Does the company split a long conduite into one ouvrage per network or per
   diameter?
5. Get 2–3 recent SONEDE / CRDA bordereaux (TUNEPS) and the bordereau of the
   current 1 200 M TND project to confirm sections 3 and 4.
