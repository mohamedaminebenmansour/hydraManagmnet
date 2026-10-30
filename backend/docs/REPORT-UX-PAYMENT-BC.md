# REPORT — Payment BC selector in main form

## Before/After

### BEFORE
```
Section 3: À qui, pour quoi (sous-traitant, libellé, pièce)
Section 4: Mode et nature (mode, bon, nature, réf)
[Plus d'options ▾] → caisse, projet, BC (caché!), facture (caché!), note
```
BC dropdown hidden under "Plus d'options" — user submits payment without linking BC.

### AFTER
```
Section 3: À qui, pour quoi (sous-traitant, libellé, pièce)
Section 4: Mode et nature (mode, bon, nature, réf)
Section 5: Contrôle matériel (BC + facture — VISIBLE)     ← NEW
[Plus d'options ▾] → caisse, projet, note                  ← simplified
```

- BC dropdown visible in main form when project is selected
- If no project: hint "Sélectionnez un projet (dans « Plus d'options ») pour lier un bon de commande"
- If project has no CONFIRMED BCs: "Aucun bon de commande confirmé sur ce projet"
- Facture dropdown next to BC (optional traçabilité)
- Warnings banner unchanged

## 3 manual test scenarios (French)

### Scénario 1: Paiement sans BC
1. Aller à Trésorerie → Nouvelle opération → "Envoyer pour validation"
2. Section 5: ne pas sélectionner de BC (laisser vide)
3. Cliquer "Envoyer pour validation"
4. Résultat: 200, pas de bannière ambre (pas de contrôle matériel)

### Scénario 2: Paiement avec BC MATCHED
1. Créer BC (confirmer) → BL (poster) → Facture (valider) → match MATCHED
2. Aller à Trésorerie → Nouvelle opération → "Plus d'options" → sélectionner le projet
3. Section 5: sélectionner le BC → les BCs confirmés du projet apparaissent
4. Cliquer "Envoyer pour validation"
5. Résultat: 200, pas de bannière ambre (toutes lignes MATCHED)

### Scénario 3: Paiement avec BC PARTIAL (pas de BL)
1. Créer BC (confirmer, sans BL)
2. Aller à Trésorerie → Nouvelle opération → "Plus d'options" → sélectionner le projet
3. Section 5: sélectionner le BC
4. Cliquer "Envoyer pour validation"
5. Résultat: 200 + bannière ambre "Ligne « Ciment » : aucune réception (BL) enregistrée"

## ng build: 0 errors ✅
