# BORDEREAU-ANALYSIS.md — Water-works bordereaux, analysed for HYDRA

Prepared 31 October 2026 from 8 public bordereaux des prix / DQE / métrés
that were actually opened and read on disk. The 9th file mentioned by the
user (Landrévarzec, France) was **not present** in `/home/z/my-project/upload/`
and could not be read; every claim below is grounded in one of F1–F8.

Each finding cites the file it comes from (F1…F8). Designations are quoted
short — no whole file copy. The Tunisian files (F1–F5) are treated as the
reference structure; the others (F6 Morocco, F7 Morocco, F8 Senegal) are used
to complete the list of articles and to test the structure against other
practices.

---

## 1. Sources

| ID | File on disk | Country / year | Type | What it covers | Prices |
|----|--------------|----------------|------|----------------|--------|
| **F1** | `upload/11645512_01.pdf` — JICA, Béja | Tunisia, 2001 | Bordereau des prix unitaires (BPU), 133 lines | PEHD pipes (DE 75–125, PN10/PN16), fonte fittings DN 50–200, earthworks, pipe laying, ouvrages courants, génie civil of small reservoirs | blank |
| **F2** | `upload/11646403.pdf` — JICA, Mahdia | Tunisia, 2001 | BPU + DQE, **two projects** (El Aitha El Bkour, Bouslim) | El Aitha El Bkour: 15 km of pipes. Bouslim: 63 km of pipes + réservoir sur piliers 250 m³ + station de reprise + abri + réservoir semi-enterré 150 m³ | blank, quantities filled |
| **F3** | `upload/11645934.pdf` — JICA, Kairouan Chelalga | Tunisia, 2001 | BPU + DQE | 25 km of pipes + 2 brise-charge 8 m³ | blank, quantities filled |
| **F4** | `upload/11646445_03.pdf` — JICA, métré | Tunisia (mentions SONEDE), 2001 | Métré | 17 km of pipes + réservoir 60 m³ + existing pumping station + local GIC + hydromechanical equipment + chlorination | quantities only |
| **F5** | `upload/11644788_02.pdf` — JICA, Final report ch. 7 | Tunisia, 2001 | Estimate template (Tableau 7.1.1) | Pipes + laying + réservoir 100 m³ + 2 brise-charge 8 m³ + pumping station (pump Q 8 m³/h, HMT 150 m) + electrical | blank |
| **F6** | `upload/AO-AVERDHA-2026-07_-Reservoir_Taous_CPS.pdf` | Morocco, 2026 | Bordereau, 39 prix | Mixed: réservoir 35 m³ + PEHD conduites DN 63 + solar pumping (panneaux 585 Wc, pompe 7.5 HP) + well deepening | blank |
| **F7** | `upload/16-DAO-construction-reservoir-AFDRIK.docx.pdf` | Morocco, 2025 | DQE, 9 lines | Small reinforced-concrete réservoir 48 m³ (single ouvrage) | blank, quantities filled |
| **F8** | `upload/DAOProjetAWA2VFrectifie2.pdf` — FAMSI/AACID | Senegal, 2018 | DQE + BPU | 5 postes d'eau potable solaires: manual boreholes, solar pumping, resin tanks on 5 m support, network, bornes fontaines, chlorination | blank, quantities filled |
| F9 | (Landrévarzec, France — mentioned by the user) | — | — | — | **NOT FOUND in `/home/z/my-project/upload/`. Cannot be analysed.** |

**Limits:** the Tunisian files are 25 years old and have no prices. Use them
for structure, séries and article designations, never for costs. Confirm
against a recent SONEDE / CRDA DCE (TUNEPS) before locking the data model.

---

## 2. How the bordereaux are organised (structure)

### 2.1 The two axes every bordereau mixes

Each article belongs to two things at once:

1. **A nature of work** — fourniture, pose et essai, terrassement, génie
   civil, équipement hydromécanique / électrique, essais, installation de
   chantier, divers.
2. **An ouvrage** — a conduite, a réservoir, a station de pompage, a
   brise-charge…

The files choose one axis for their sections and leave the other implicit.
Five different patterns were observed:

| Pattern | Sections are… | Files | Consequence for HYDRA |
|---------|---------------|-------|-----------------------|
| **A.** By nature of work, numbered séries | Série 100 fourniture PEHD · 150 robinetterie fonte · 200 terrassement · 210 pose · 220 ouvrages courants · 300 génie civil (sous-séries 310, 320, 340) | **F1** (verified lines 49–71 of `11645512_01.txt`) | The ouvrage must be found line by line (keywords, DN, "réservoir"). The civil works of several ouvrages share the same lines. |
| **B.** By nature of work, then one sub-section per ouvrage for civil works | I Fourniture · II Pose et essai · III Terrassement · IV Génie civil → 41 Ouvrages de petite hydraulique · 42 Réservoir sur piliers 250 m³ · 43 Station de reprise + abri · 44 Réservoir semi-enterré 150 m³ | **F2** (verified lines 486, 521, 600, 707, 883, 898, 929, 967 of `11646403.txt`); **F4** (sections 2.1 … 2.8, with 2.6 = réservoir 60 m³, 2.7 = station, 2.8 = équipement hydromécanique — verified lines 143, 171, 357 of `11646445_03.txt`); **F5** (I fourniture · II réseaux · III-1 réservoir + brise-charge · III-2 station · IV hydromécanique et électrique — verified lines 138–244 of `11644788_02.txt`) | Pipe sections go to the conduite; each civil-works sub-section maps to exactly one ouvrage. |
| **C.** By contract package (sous-lot) | Sous-lot I fourniture · Sous-lot II terrassement + ouvrages courants + pose · Sous-lot III construction de 2 brise-charge 8 m³ | **F3** (verified lines 659, 718, 776, 809 of `11645934.txt`) | Sous-lot III = one ouvrage type (×2 instances). Sous-lots I and II = conduite. |
| **D.** By ouvrage | Série I Réservoir · II Conduites · III Pompage solaire · IV Puits | **F6** (verified — Chapitre III of `AO-AVERDHA-2026-07`); **F8** (sections 1–8: Installation · Générateur PV · Pompage · Réseau production · Réseau distribution · Borne fontaine · Réservoir · Traitement eau — verified in the DQE page of `DAOProjetAWA2VFrectifie2.pdf`) | Each section maps directly to one ouvrage. |
| **E.** Flat list | 9 numbered lines, no séries | **F7** (verified — the BPU/DQE table at the end of `16-DAO-construction-reservoir-AFDRIK`) | Whole file = one ouvrage (the réservoir 48 m³). |

**Design rule for HYDRA:** store the section path exactly as written (lot /
sous-lot / série / sous-série), and *separately* store `ouvrage_id` and
`work_nature`. Never derive one from the other.

### 2.2 Supply and laying are separate lines for the same pipe

In **F2** and **F3** the same pipe appears twice with the same length:
"Conduite PEHD PN10 DE 110 — fourniture" and "Pose et essai conduite PEHD
DE 110" (F2, séries 100 then 210 in `11646403.txt`). **F3** does the same
between sous-lot I and sous-lot II (supply 5 320 ml, laying 5 067.1 ml for
DE 160, so laying is about 95 % of supply: an allowance for waste).

**F8** splits the same item differently: in the **DQE** the network
pipe is counted in "unité" (one per poste), but in the **BPU** it is priced
"au mètre linéaire" (verified in `DAOProjetAWA2VFrectifie2.pdf`, sections 6.1
and 7.1).

Consequence for HYDRA:
- In attachements, supply can be paid on delivery to site and laying on
  progress. HYDRA must track both quantities for the same pipe.
- Add `paired_line_id` on `bordereau_line` to link a fourniture line to its
  pose line. The import can suggest pairs when designation + material + DE +
  PN match.

### 2.3 Article numbering is free text

Numbers found in the files:
- `101a`, `101b`, `102a`, `105a`…`105h`, `343b` — **F1** (verified lines
  49–71 of `11645512_01.txt`).
- `A2.1` … `A2.17`, with `A2.12` missing and `A2.13`, `A2.17` reused in
  sous-lot II — **F3** (verified lines 80–265 of `11645934.txt`).
- `1.2.7.4`, `6.7.16.3`, and an out-of-place `6.8.15` inside série 6.7 —
  **F4** (verified by grep on `11646445_03.txt`).
- `42-14` style (sub-series 42, line 14) — **F2** (verified lines 521, 883
  of `11646403.txt`).
- `Prix N°1` restarting at 1 in each série — **F6** (verified in the
  bordereau pages of `AO-AVERDHA-2026-07`).
- `1`, `2`, `3` … `9` flat list — **F7** (verified — last page of
  `16-DAO-construction-reservoir-AFDRIK`).
- `1.1`, `2.1`, `3.1` … `10.1` — **F8** (verified in the DQE/BPU pages of
  `DAOProjetAWA2VFrectifie2.pdf`).

Consequence: `article_no` is `text`, not unique on its own. Uniqueness is
(`version_id`, `section_path`, `article_no`). Keep a `sort_order` integer for
display order.

### 2.4 Units must be normalised

The same unit is written many ways. Verified by counting occurrences in the
extracted text:

| Normalised | Variants actually seen | Files |
|------------|------------------------|-------|
| `ML` (mètre linéaire) | `ml`, `ML`, `Ml`, `mètre linéaire`, `mètre Linéaire` | F1, F2, F3, F5, F6, F8 |
| `M3` | `m³`, `m3`, `M3`, `mètre cube` | F1, F2, F3, F4, F5, F6, F7 |
| `M2` | `m²`, `m2`, `M2` | F1, F2, F5, F6, F7 |
| `U` | `U`, `u`, `unité`, `l'unité` | all |
| `KG` | `kg`, `KG` | F1, F2, F3, F6, F7 |
| `ENS` | `Ens`, `ENS`, `Ens.`, `ensemble` | F2, F4, F8 |
| `FORFAIT` | `forfait`, `Forf`, `FF` | F1, F2, F6, F8 |
| `LOT` | `lots` | F5 |

**F1** spells the unit out ("mètre linéaire", "mètre cube", "l'unité") —
verified 93× "unité", 18× "mètre linéaire", 16× "mètre cube" in
`11645512_01.txt`. **F2** uses abbreviations ("U", "M2", "M3", "ML", "ENS",
"Forf", "Ml") — verified 152× "U", 70× "unité", 32× "M2", 29× "M3", 24×
"ML", 8× "kg", 3× "ENS", 2× "Forf" in `11646403.txt`.

Keep `unit_raw` as written and `unit` normalised. Flag, don't fix,
suspicious units: F7 line 1 "F" for excavation (should be M3); F7 line 7
"U" with quantity 137.92 of enduit (should be M2 — verified in the DQE
table of `16-DAO-construction-reservoir-AFDRIK`).

### 2.5 Concrete names are not comparable between files — verified

This is the single most important inconsistency. Same label, different
dosage:

- **F1**: `Béton B1 (150 kg/m3)`, `Béton B2 (250 kg/m3)`, `Béton B3 (350
  kg/m3)` — verified lines 118–120 and 967–991 of `11645512_01.txt`
  (propreté 150, fondation 250, béton armé 350).
- **F2**: `Béton B1 de propreté dosé à 250 kg`, `Béton B3 pour fondation
  dosé à 250 kg/m3`, `Béton B4 dosé à 350 kg`, `Béton B5 dosé à 350 kg` —
  verified lines 530, 533, 540, 543, 616, 619, 626, 715–719, 900–903 of
  `11646403.txt`. Here **B1 = 250 kg** (not 150 like F1) and **B3 = 250 kg**
  (not 350 like F1).
- **F5**: uses `Béton armé de préparation (B1)` and `Travaux de
  renforcement du béton (B5)` — verified lines 145–152 of
  `11644788_02.txt`.
- **F4, F6, F7** write the dosage directly: 350, 400 kg/m³ — verified in
  F6 prix 5 ("béton hydrofuge dosé 400 kg") and F7 line 3 ("Béton armé dosé
  400 kg/m3").

**Store the dosage in kg/m³ as a characteristic; never key on the label
"B3".** An import that assumes "B3 = 350 kg" would silently mis-price
foundation concrete in F2.

### 2.6 TVA is a configuration value, not a constant

**F2** shows "TVA (18 %)" — verified in `11646403.txt`. The Tunisian TVA on
public works in 2001 was 18 %; it has since changed several times. Tax rates
must be configuration with effective dates, never constants in code.

---

## 3. Séries / sections found and the ouvrage type they belong to

"Files" = files containing a section (or a clear group of lines) of that
kind. Each row cites the verification source.

| Normalised série / section | Typical labels in the files | Files | Ouvrage type |
|----------------------------|-----------------------------|-------|--------------|
| Installation et repli de chantier | "Installation, amenée et repli"; "amenée du matériel"; "repli du chantier" | F8 (DQE section 1.1, 1.2) | COMMUN |
| Fourniture conduites et pièces PEHD | "Série 100"; "I Fourniture"; "Sous-lot I A1–A2"; "2.1"; "I-1 Conduite PEHD PN10 DE90" | F1, F2, F3, F4, F5 | CONDUITE |
| Fourniture robinetterie et pièces fonte / acier | "Série 150"; "A2.2 Vannes, A2.13 joints, A2.16 compteur" | F1, F2, F3, F4, F5 | CONDUITE (or the ouvrage where installed) |
| Terrassement pour conduites | "Série 200"; "III Terrassement (tranchée 60 cm)"; "Sous-lot II A1"; "II-1-1 fouilles" | F1, F2, F3, F4, F5, F6 | CONDUITE |
| Pose et essai de conduites | "Série 210"; "II Pose et essai"; "Sous-lot II B"; "II-3-1 PEHD DE90 PN10" | F1, F2, F3, F4, F5 | CONDUITE |
| Ouvrages courants sur conduite | "Série 220"; "41 Ouvrages de petite hydraulique"; "Sous-lot II A2"; "II-3-3 soupape" | F1, F2, F3, F4, F6 | OUVRAGE_ANNEXE (or CONDUITE) |
| Ouvrages de distribution | "Borne fontaine, potence, branchements, traversées"; "II-5" | F1, F2, F3, F4, F5, F8 | BRANCHEMENTS |
| Génie civil réservoir | "Série 300 (310, 320, 340)"; "42 Réservoir sur piliers 250 m³"; "44 Réservoir semi-enterré 150 m³"; "2.6 Réservoir 60 m³"; "III-1 Réservoir 100m³ + 2 brises charges 8m³"; "Série I Réservoir"; F7 whole file; F8 section 7 | F1, F2, F4, F5, F6, F7, F8 | RESERVOIR |
| Génie civil brise-charge / bâche de reprise | "Sous-lot III Construction de 2 brises charge 8 m³"; "III-1 (réservoir 100 m³ + 2 brises charges 8 m³)" | F3, F5 | RESERVOIR (subtype BRISE_CHARGE) |
| Génie civil station de pompage / reprise, local | "43 Station de reprise + abri"; "2.7 local GIC"; "III-2 Construction de la station de pompage" | F2, F4, F5 | STATION_POMPAGE |
| Équipement hydromécanique station | "2.8 Équipement hydromécanique de la station de pompage et de la chambre de chloration"; "IV-1 Équipement de pompe" | F4, F5 | STATION_POMPAGE |
| Équipement électrique / énergie | "IV-3 tableau, câbles, transformateur 25 kVA"; "Série III pompage solaire"; "section 2 générateur PV" | F5, F6, F8 | STATION_POMPAGE |
| Traitement / chloration | "Chambre de chloration (2.7–2.8)"; "IV-2 javellisation"; "section 8 chlorateur" | F4, F5, F8 | STATION_POMPAGE (or RESERVOIR) |
| Forage / puits | "Forage manuel A1–A10"; "tête de forage"; "puits traditionnel"; "Série IV Approfondissement d'un puits" | F4, F6, F8 | FORAGE |
| Travaux sous voirie | "Forage horizontal sous route"; "réfection chaussée"; "traversées" | F1, F2, F4 | CONDUITE |
| Essais | "Épreuves d'étanchéité"; "essais de débit"; "essai de remplissage" | F6, F8 (and "pose et essai" lines everywhere) | CONDUITE / FORAGE / RESERVOIR |
| Branchements / bornes fontaines | "Borne fontaine", "Branchement public", "Branchement particulier" | F1, F2, F3, F4, F5, F8 | BRANCHEMENTS |

---

## 4. Recurring articles (the 40 most frequent, normalised)

Designations shortened and normalised; `{}` = a value that varies (becomes a
characteristic). "Nature" = work_nature. Each row cites the file(s) where
the article was actually seen.

| # | Normalised designation | Unit | Ouvrage type | Nature | Identifying keywords | Files |
|---|------------------------|------|--------------|--------|----------------------|-------|
| 1 | Conduite PEHD DE {de} PN {pn} (fourniture) | ML | CONDUITE | FOURNITURE | PEHD, DE, PN | F1, F2, F3, F4, F5, F6, F8 |
| 2 | Pose et essai conduite PEHD DE {de} | ML | CONDUITE | POSE | pose, essai, DE | F1, F2, F3, F4, F5 |
| 3 | Fouille / déblai en tranchée | M3 | CONDUITE | TERRASSEMENT | fouille, déblai, tranchée, rigole | F1, F2, F3, F4, F5, F6 |
| 4 | Remblai de tranchée (compacté) | M3 | CONDUITE | TERRASSEMENT | remblai, remblaiement | F1, F2, F3, F4, F5, F6 |
| 5 | Lit de pose en sable (10 cm) | M3 | CONDUITE | TERRASSEMENT | sable, lit de pose | F1, F2, F3, F4, F5, F6 |
| 6 | Plus-value terrain rocheux | M3 | CONDUITE | TERRASSEMENT | rocheux, rocher, plus-value | F4, F6 |
| 7 | Robinet-vanne fonte DN {dn} PN {pn} | U | CONDUITE | FOURNITURE | robinet vanne, vanne, opercule | F1, F2, F3, F4, F5, F6, F8 |
| 8 | Ventouse simple / double effet DN {dn} | U | CONDUITE | FOURNITURE | ventouse | F1, F2, F3, F4, F5 |
| 9 | Joint de démontage DN {dn} | U | CONDUITE | FOURNITURE | joint de démontage | F1, F2, F3, F4 |
| 10 | Manchette bridée fonte DN {dn} L {l} | U | CONDUITE | FOURNITURE | manchette | F1, F2, F3, F4 |
| 11 | Té PEHD / Té fonte bridé {dn1}/{dn2} | U | CONDUITE | FOURNITURE | té | F1, F2, F3, F4, F5 |
| 12 | Réduction / cône {dn1}/{dn2} | U | CONDUITE | FOURNITURE | réduction, cône | F1, F2, F3, F4, F5 |
| 13 | Coude PEHD 90° / 45° / 30° DE {de} | U | CONDUITE | FOURNITURE | coude | F1, F2, F3, F4 |
| 14 | Collet bridé PEHD DE {de} / DN {dn} | U | CONDUITE | FOURNITURE | collet, raccord avec bride | F1, F2, F3, F4 |
| 15 | Compteur volumétrique DN {dn} | U | CONDUITE or STATION | FOURNITURE | compteur | F1, F2, F3, F4, F5 |
| 16 | Clapet anti-retour DN {dn} | U | CONDUITE or STATION | FOURNITURE | clapet | F1, F2, F4 |
| 17 | Collier de prise en charge DE {de}/{de2} | U | BRANCHEMENTS | FOURNITURE | collier de prise | F1, F3 |
| 18 | Ouvrage / regard de ventouse (point haut) | U | OUVRAGE_ANNEXE | GENIE_CIVIL | regard de ventouse, point haut | F1, F2, F3, F4, F6 |
| 19 | Ouvrage / regard de vidange (point bas) | U | OUVRAGE_ANNEXE | GENIE_CIVIL | vidange, point bas | F1, F2, F3, F4, F6 |
| 20 | Ouvrage de sectionnement (type or dimensions) | U | OUVRAGE_ANNEXE | GENIE_CIVIL | sectionnement | F1, F2, F3, F4, F6 |
| 21 | Ouvrage de piquage | U | OUVRAGE_ANNEXE | GENIE_CIVIL | piquage | F1, F2 |
| 22 | Borne fontaine | U | BRANCHEMENTS | GENIE_CIVIL | borne fontaine, BF | F1, F2, F3, F4, F5, F8 |
| 23 | Potence | U | BRANCHEMENTS | GENIE_CIVIL | potence | F2, F4, F5 |
| 24 | Branchement public / particulier | U | BRANCHEMENTS | POSE | branchement | F1, F2, F3, F4 |
| 25 | Traversée de route / piste | ML | CONDUITE | GENIE_CIVIL | traversée, passage busé, forage horizontal | F2, F4 |
| 26 | Grillage avertisseur | ML | CONDUITE | FOURNITURE | grillage avertisseur | F2, F6 |
| 27 | Raccordement sur conduite existante (SONEDE / GR) | FORFAIT | CONDUITE | POSE | raccordement, existante, SONEDE | F2, F4, F5 |
| 28 | Fouille en pleine masse pour ouvrage | M3 | RESERVOIR / STATION | TERRASSEMENT | pleine masse | F1, F2, F3, F4, F6, F7 |
| 29 | Béton de propreté (≈150–250 kg/m³) | M3 or M2 | RESERVOIR / STATION / ANNEXE | GENIE_CIVIL | propreté, B1 | F1, F2, F3, F4, F5, F6, F7 |
| 30 | Béton armé dosé {dosage} kg/m³ | M3 | RESERVOIR / STATION / ANNEXE | GENIE_CIVIL | béton armé, dosé, hydrofuge | F1, F2, F3, F4, F5, F6, F7 |
| 31 | Acier haute adhérence (HA) | KG | RESERVOIR / STATION / ANNEXE | GENIE_CIVIL | acier HA, haute adhérence, Fe E 500 | F1, F2, F3, F6, F7 |
| 32 | Enduit étanche / cuvelage / hydrofuge | M2 | RESERVOIR | GENIE_CIVIL | étanche, cuvelage, hydrofuge | F1, F2, F3, F4, F6 |
| 33 | Enduit ordinaire | M2 | RESERVOIR / STATION | GENIE_CIVIL | enduit | F2, F3, F4, F6, F7 |
| 34 | Badigeon Flintkote / surfacer / chaux | M2 | RESERVOIR / STATION | GENIE_CIVIL | badigeon, Flintkote, surfaçage | F1, F2, F3, F4 |
| 35 | Joint d'étanchéité (reprise de bétonnage) | ML | RESERVOIR | GENIE_CIVIL | joint d'étanchéité | F1, F2, F4 |
| 36 | Trappe d'entrée (tôle) | U | RESERVOIR | EQUIPEMENT | trappe | F1, F2, F4, F6, F7 |
| 37 | Échelle (alu / acier galvanisé) | U or ML | RESERVOIR | EQUIPEMENT | échelle | F1, F2, F4, F6 |
| 38 | Porte ventilée / châssis d'aération | U | RESERVOIR / STATION | EQUIPEMENT | porte ventilée, aération | F1, F2, F3, F4 |
| 39 | Robinet à flotteur DN {dn} | U | RESERVOIR | EQUIPEMENT | flotteur | F1, F2, F3, F4, F5 |
| 40 | Crépine bronze DN {dn} | U | RESERVOIR / STATION | EQUIPEMENT | crépine | F1, F2, F3, F4, F5 |
| 41 | Lignes arrivée / départ / trop-plein / vidange DN {dn} | U | RESERVOIR | EQUIPEMENT | ligne d'arrivée, départ, trop-plein | F1, F4 |
| 42 | Étanchéité de toiture multicouche | M2 | RESERVOIR / STATION | GENIE_CIVIL | étanchéité, toiture | F1, F2, F4 |
| 43 | Clôture + portail | ML or U | RESERVOIR / STATION | DIVERS | clôture, portail | F1, F2, F6, F8 |
| 44 | Pompe immergée / électrique Q {q} m³/h HMT {hmt} m, {kw} kW | U | STATION_POMPAGE | EQUIPEMENT | pompe immergée, HMT, débit | F5, F6, F8 |
| 45 | Tête de forage / colonne montante DN {dn} | U | FORAGE | EQUIPEMENT | tête de forage, colonne | F4, F8 |
| 46 | Chloration: pompe doseuse / chlorateur | U | STATION_POMPAGE | EQUIPEMENT | chlor, javellisation | F4, F5, F8 |
| 47 | Équipement électrique: tableau, câbles, transformateur / solaire, variateur | U, ML | STATION_POMPAGE | EQUIPEMENT | câble, tableau, transformateur, panneaux, variateur | F5, F6, F8 |
| 48 | Panneaux solaires photovoltaïques {wc} Wc | U | STATION_POMPAGE | EQUIPEMENT | panneaux, photovoltaïque, Wc | F6, F8 |
| 49 | Installation et repli de chantier | ENS / FORFAIT | COMMUN | INSTALLATION | installation, repli, amenée | F8 |
| 50 | Carrelage / revêtement intérieur cuve | M2 | RESERVOIR | GENIE_CIVIL | carrelage, revêtement | F6, F7 |

---

## 5. Characteristics each ouvrage type needs

Values seen in the files are given as evidence for the allowed values.

### CONDUITE
| Key | Type | Values seen (file) |
|-----|------|--------------------|
| `usage` | enum | ADDUCTION, REFOULEMENT, DISTRIBUTION (F8 separates "réseau production" from "réseau distribution") |
| `materiau` | enum | PEHD (F1, F2, F3, F4, F5), FONTE (F1 série 150), PVC, ACIER_GALVANISE (F6 prix 17, F8), AC (existing pipes) |
| `de_mm` (PEHD/PVC outside diameter) | integer | 25 (F8), 32 (F8), 63 (F6), 75, 90, 110, 125, 160 (F3), 200, 250 |
| `dn_mm` (fonte/acier nominal diameter) | integer | 40, 50, 60, 65, 80, 100, 150, 200, 250, 300 |
| `pn_bar` | enum | 10, 16 |
| `assemblage` | enum | ELECTROSOUDABLE (F1), BOUT_A_BOUT, BRIDE, JOINT_CAOUTCHOUC (F6 prix 4) |
| `longueur_m` | number | 1 404 to 21 915 ml per diameter (F2 Bouslim) |
| `largeur_tranchee_m` | number | 0.60 (F2), up to 0.70 (F3) |

A conduite ouvrage can hold several diameters (F2 Bouslim: DE 250 down to
DE 90 on one network). Either one ouvrage per diameter-run, or one ouvrage
"Réseau X" with a list of `troncons` [{de_mm, pn_bar, longueur_m}].
Recommended: the list, because the bordereau lines are per diameter but the
site thinks per network.

### RESERVOIR
| Key | Type | Values seen (file) |
|-----|------|--------------------|
| `sous_type` | enum | SEMI_ENTERRE (F2 150 m³), SURELEVE / SUR_PILIERS (F2 250 m³), BRISE_CHARGE (F3 8 m³, F5 8 m³), BACHE_REPRISE (F2 station de reprise), CUVE_SUR_SUPPORT (F8 résine 5–8 m³ sur support 5 m), ENTERRE (F7) |
| `capacite_m3` | number | 5 (F8), 6 (F8), 8 (F8, F3, F5), 35 (F6), 48 (F7), 60 (F4), 100 (F5), 150 (F2), 250 (F2) |
| `materiau` | enum | BETON_ARME (F1–F5, F6, F7), RESINE (F8 polyester), MACONNERIE (F6 moellon) |
| `dosage_beton_kg_m3` | integer | 150 (F1 B1), 250 (F1 B2 / F2 B1, B3), 300 (F6 prix 6, F6 regard 0.8×0.8), 350 (F1 B3 / F2 B4, B5 / F4), 400 (F5 / F6 prix 5 hydrofuge / F7 line 3) |
| `revetement_interieur` | enum | ENDUIT_ETANCHE, CUVELAGE (F1 série 326), CARRELAGE (F6 prix 11, F7 line 8), ENDUIT_HYDROFUGE (F6 prix 8) |
| `dn_arrivee`, `dn_depart`, `dn_trop_plein`, `dn_vidange` | integer | 60–200 (F1 prix 343–346, F4 section 2.7, F6 prix 5 "bride majeure") |
| `cloture` | boolean | F1 prix 355, F2, F6 prix 13 |

### STATION_POMPAGE
| Key | Type | Values seen (file) |
|-----|------|--------------------|
| `sous_type` | enum | STATION_REPRISE (F2 43), POMPAGE_FORAGE (F4, F6, F8) |
| `debit_m3h` | number | 4 (F8 pompe immergée 4 m³/h), 8 (F5 Q 8 m³/h) |
| `hmt_m` | number | 40 (F8), 150 (F5), 200 (F6 pompe 7.5 HP, HMT=200 m) |
| `nb_pompes` | integer | 1 per site in the files |
| `puissance_kw` | number | 5.5 (F6 pompe 7.5 HP = 5.5 kW) |
| `energie` | enum | RESEAU_ELECTRIQUE (F5 transformateur 25 kVA), SOLAIRE (F6 panneaux 585 Wc + variateur 5.5 kW, F8 panneaux 230 Wc) |
| `chloration` | boolean | F4 chambre de chloration, F5 javellisation, F8 section 8 chlorateur |
| `local_technique` | boolean | F2 abri 43, F4 local GIC, F8 local sous support |

### FORAGE
| Key | Type | Values seen (file) |
|-----|------|--------------------|
| `type` | enum | FORAGE_MECANIQUE, FORAGE_MANUEL (F8 "forage manuel"), PUITS (F6 "approfondissement d'un puits traditionnel") |
| `profondeur_m` | number | not stated in any file (F8 plans show 40 m but no line in the bordereau) |
| `diametre_tubage_mm` | number | DN 25 colonne d'exhaure PEHD (F8), DN 150 PVC cuvelage (F8), DN 63 (F6) |
| `materiau_tubage` | enum | PVC (F8 "tuyau de forage PVC spécial"), ACIER |
| `debit_exploitation_m3h` | number | from essais de débit (F8 DQE) |

### OUVRAGE_ANNEXE (on a conduite)
| Key | Type | Values seen (file) |
|-----|------|--------------------|
| `type` | enum | VENTOUSE (F1 prix 221), VIDANGE (F1 prix 222), SECTIONNEMENT (F1 prix 223 type I, prix 224 type II), PIQUAGE (F1 prix 226), REDUCTEUR_PRESSION (F1 prix 225), BRISE_CHARGE_EN_LIGNE |
| `modele` | text | "type I", "type II" (F1), "type P1", "P2.1", "P4" (F4) |
| `dimensions` | text | "0.80 × 0.80" (F6 prix 13 regard 0.8×0.8), "0.5 × 0.5" (F8 regard 0.5×0.5), "0.6 × 0.5 × 0.4" (F8 regard borne fontaine) |
| `pk` | number | position on the conduite — not in the files; needed for site progress |

These are counted in units (dozens per project: F2 Bouslim has many ventouse
and vidange regards). Do **not** create one HYDRA ouvrage per regard. Treat
them as quantities inside the CONDUITE (or one OUVRAGE_ANNEXE per type), and
record PK positions only if the site wants them.

### BRANCHEMENTS
| Key | Type | Values seen (file) |
|-----|------|--------------------|
| `nb_bornes_fontaines` | integer | 5 (F8), up to 42 (F2 Bouslim) |
| `nb_potences` | integer | 3–4 (F4) |
| `nb_branchements_publics` | integer | 5 (F4: école, dispensaire, mosquée) |
| `nb_branchements_particuliers` | integer | 3–10 |

### COMMUN
Installation et repli, plans d'exécution, essais globaux, raccordement au
réseau existant, sécurité. No characteristics.

---

## 6. Anything unusual or inconsistent between files

| File | Problem | Source line / page |
|------|---------|-------------------|
| F1 | Prix 107 "Réduction en Pehd PN10" includes a sub-line "Réduction DE 110/DE 750" — typo for 75. Lines 155, 161, 162, 165 have no unit in the form (only HT/Taxes/TTC). | `11645512_01.txt` lines 59, 155, 161–165 |
| F2 | Two lines numbered 5 in El Aitha El Bkour. DQE units "ENS" / "Forf" differ from the bordereau "U". "Plancher corps creux" priced in kg in one column and m² in the other. | `11646403.txt` (Bouslim + El Aitha sub-projects) |
| F3 | `A2.12` is missing (jumps from A2.11 to A2.13). `A2.13` and `A2.17` are reused in sous-lot II. Several items all numbered "1" or "11" inside one group. | `11645934.txt` lines 80–265, 607, 637 |
| F4 | `1.2.11.3` and `1.2.11.4` are identical. Line `6.8.15` is inside série 6.7 (out-of-place). Section 2.8 has a "Nombre" column but no unit. | `11646445_03.txt` |
| F5 | Template: header rows have no unit (only leaf lines are priced). | `11644788_02.txt` lines 138–244 |
| F6 | Série III prix 13 is titled "siège" but describes a fence (clôture). Série IV prix numbered 1 but referenced as prix 14 in the body. Two separate "Prix N°1" appear in the document (one per série). | `AO-AVERDHA-2026-07` bordereau pages |
| F7 | Unit "F" for excavation (should be M3). Plaster / enduit quantity 137.92 in "U" (should be M2). The end-of-file BPU table mixes units inconsistently. | `16-DAO-construction-reservoir-AFDRIK` DQE table |
| F8 | DQE section 7.3 is missing (jumps from 7.2 to 7.4). The grand total `TOTAL (1+2+3+4+5+6+7)` excludes section 8 (traitement eau). DQE counts the same pipe in "unité" while the BPU prices it "au mètre linéaire". | `DAOProjetAWA2VFrectifie2.pdf` DQE + BPU pages |
| F9 | **File not present in `/home/z/my-project/upload/` — could not be opened.** | — |

### Inconsistencies between files (cross-file)

1. **Béton labels are not portable.** B3 = 350 kg/m³ in **F1** (lines
   118–120, 991) but B3 = 250 kg/m³ for foundations in **F2** (lines 533,
   619). B1 = 150 kg/m³ in **F1** (line 967) but B1 = 250 kg/m³ in **F2**
   (line 530). Store the dosage as a number, never key on the label.
2. **Same pipe, two lines.** Fourniture + pose are split into separate
   priced lines in **F1, F2, F3** but merged into a single "fourniture et
   pose" line in **F6, F7, F8**. The HYDRA `paired_line_id` must be optional,
   not required.
3. **Units are not stable.** F1 spells units out; F2 uses ML/U/M3/M2; F6
   uses `mètre Linéaire` with a capital; F7 uses `F` for excavation; F8
   uses `FF` for "forfait" and `U` for "unité" interchangeably.
   Normalisation is mandatory (see §2.4).
4. **Article numbering resets per section.** `Prix N°1` in F6 série I, II,
   III, IV are four different articles. The uniqueness key must include the
   section path (see §2.3).
5. **TVA rate is in the file.** F2 shows "TVA (18 %)" — this is the 2001
   Tunisian rate, not the current one. Tax rates must be configuration with
   effective dates, never constants.

### Import rules derived from this

1. Never reject a file for these problems. Import, then show warnings per
   line.
2. A line with a designation but no unit and no quantity = a section title.
3. A line with a unit but no quantity = keep it with quantity 0 and a
   warning.
4. Duplicate `article_no` inside the same section = keep both, warn.
5. Unknown unit = keep `unit_raw`, set `unit` null, warn.
6. Béton labels (B1, B2, B3, B4, B5) = never trust the label; require the
   dosage to be present in the designation text and extract it with a regex
   like `dos[ée][^0-9]*(\d{3})\s*kg`.

---

## 7. Open questions

1. **France file (F9).** The user mentioned a Landrévarzec (France) file but
   it is not in `/home/z/my-project/upload/`. It could not be analysed. If
   the user provides it later, sections 3 and 4 may need one more column.
2. **Does the company receive bordereaux as Excel or only PDF?** Decides
   whether the import must handle PDF tables or can rely on a structured
   template.
3. **In attachements, are supply and laying of pipes paid separately, as
   the bordereaux suggest?** Decides whether `paired_line_id` is just
   cosmetic or drives the payment workflow.
4. **Should regards (ventouse, vidange, sectionnement) be tracked
   individually with PK on site, or only as quantities?** F2 Bouslim has
   dozens of them; creating one ouvrage per regard would clutter the
   chantier.
5. **Does the company split a long conduite into one ouvrage per network or
   per diameter?** See the discussion of `troncons` in §5.
6. **Béton label drift.** Should HYDRA forbid B1/B2/B3 labels in new
   bordereaux and require the dosage in kg/m³ explicit?
