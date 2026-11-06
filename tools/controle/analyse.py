"""Analyse data.json (from convert.py) and the source xlsx -> problems workbook.
Usage: python3 analyse.py data.json in.xlsx out.xlsx
"""
import json, re, sys, unicodedata, collections, warnings
import openpyxl
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.datavalidation import DataValidation

warnings.filterwarnings('ignore')
data = json.load(open(sys.argv[1]))
SRC, OUT = sys.argv[2], sys.argv[3]
GRP = ['Hajeb', 'Hammed', 'Ouled Khalfallah']
ACC = {a['id']: a['name'] for a in data['accounts']}
STA = {a['id']: a for a in data['stAccounts']}

def plain(s):
    s = unicodedata.normalize('NFD', (s or '').lower())
    s = ''.join(c for c in s if unicodedata.category(c) != 'Mn')
    return re.sub(r'\s+', ' ', s).strip()

def article(l):
    """Normalized article (poste of the marché) from the Excel 'Destination / ST' column."""
    raw = l['cat'] or ''
    p = plain(raw)
    if not p or p in ('-', 'ngnet', 'sigat'):
        return 'Non classé'
    if p.startswith('st ') or p.startswith('s.t') or 'sous tra' in p:
        return 'ST ' + (l['party'] or raw[3:].strip()).strip()
    if 'charge sur ngnet' in p: return 'Charge sur marché'
    if 'charge' in p and 'march' in p: return 'Charge sur marché'
    m = re.match(r'mo ?(rsp|rse) ?(\d+)?', p)
    if m:
        if m.group(1) == 'rsp': return 'MO RSP'
        return 'MO RSE ' + (m.group(2) if m.group(2) else '(à préciser)')
    if 'existant' in p: return 'Réservoir existant'
    m = re.match(r'rse ?(\d+)', p)
    if m: return 'RSE ' + m.group(1)
    if p == 'rse': return 'RSE (à préciser)'
    if p.startswith('rsp'): return 'RSP'
    if 'pehd' in p: return 'Conduite en PEHD'
    if 'fonte' in p: return 'Conduite en fonte'
    if 'niche' in p: return 'Pièces des niches'
    if 'speciale' in p: return 'Pièces spéciales'
    if p.startswith('abri'): return 'Abri forage'
    if 'cloture' in p: return 'Clôture'
    if 'surpress' in p: return 'Station de surpresseur'
    if 'caisse chantier' in p: return 'Alimentation caisse chantier'
    if p.startswith('encaissement'): return 'Encaissement (décomptes)'
    if p == 'bc': return 'BC (à préciser)'
    if 'travaux' in p or 'ouvrage' in p: return 'Travaux divers / GC'
    if p.startswith('projet'): return 'Non classé'
    if re.match(r'^[a-z]+( [a-z]+){1,2}$', p):
        return 'Alimentation (personne) — ' + raw.strip().title()
    return raw.strip()

def piece(l):
    f, b = (l['fct'] or '').strip(), (l['bon'] or '').strip()
    hasf, hasb = bool(re.search(r'\d', f)), bool(re.search(r'\d', b))
    if hasf and hasb: return 'Facture + bon'
    if hasf: return 'Facture'
    if hasb: return 'Bon'
    if (f and f != '-') : return 'Mention sans numéro'
    return 'Aucune pièce'

def out_amt(l): return 0.0 if l['kind'] == 'transfert' else l['d']
def in_amt(l): return 0.0 if l['kind'] == 'transfert' else l['e']

L = data['lines']
for l in L:
    l['art'] = article(l); l['pc'] = piece(l)

FLAG = {'especes': 'Espèces > 5 000 TND', 'hors-compte': 'Payé à un ST mais absent de son compte ST',
        'benefice': 'Avance sur bénéfice', 'doublon': 'Doublon possible', 'interne': 'Transfert compté deux fois',
        'date': 'Date impossible', 'sans-date': 'Sans date', 'non-retrouve': 'Paiement ST non retrouvé dans les caisses'}

# ---------------------------------------------------------------- workbook helpers
F = 'Arial'; HFONT = Font(name=F, bold=True, color='FFFFFF'); HFILL = PatternFill('solid', fgColor='1F4E5A')
YEL = PatternFill('solid', fgColor='FFF2CC'); NUM = '#,##0.000;[Red]-#,##0.000;"-"'
SEV = {'Critique': 'F8D7DA', 'Important': 'FCE8C8', 'À vérifier': 'E7F1FB'}
wb = Workbook()

def sheet(title, cols, rows, widths, num=(), yellow=(), wrap=(), sev_col=None):
    ws = wb.create_sheet(title)
    for j, c in enumerate(cols, 1):
        x = ws.cell(1, j, c); x.font = HFONT; x.fill = HFILL
        x.alignment = Alignment(wrap_text=True, vertical='center')
    for i, r in enumerate(rows, 2):
        for j, v in enumerate(r, 1):
            x = ws.cell(i, j, v); x.font = Font(name=F, size=10)
            c = cols[j - 1]
            if c in num: x.number_format = NUM
            if c in yellow: x.fill = YEL
            if c in wrap: x.alignment = Alignment(wrap_text=True, vertical='top')
            if c == sev_col and v in SEV: x.fill = PatternFill('solid', fgColor=SEV[v])
    for j, w in enumerate(widths, 1): ws.column_dimensions[get_column_letter(j)].width = w
    ws.row_dimensions[1].height = 32
    ws.freeze_panes = 'A2'
    if rows: ws.auto_filter.ref = f'A1:{get_column_letter(len(cols))}{len(rows) + 1}'
    return ws

def r3(x): return round(x, 3)

# ---------------------------------------------------------------- 1. Excel totals vs lines
src = openpyxl.load_workbook(SRC, data_only=True, read_only=True)
tot_rows = []
by_sheet = collections.defaultdict(lambda: [0.0, 0.0, 0])
for l in L:
    s = by_sheet[l['sheet']]; s[0] += l['e']; s[1] += l['d']; s[2] += 1
st_sheet = collections.defaultdict(lambda: [0.0, 0.0, 0])
for s in data['stLines']:
    x = st_sheet[s['sheet']]; x[0] += s['travaux']; x[1] += s['paye']; x[2] += 1
for ws in src.worksheets:
    if ws.title not in by_sheet and ws.title not in st_sheet: continue
    excel = None
    for r in ws.iter_rows(min_row=1, max_row=8, values_only=True):
        if any(isinstance(c, str) and c.strip().lower() == 'totaux' for c in r):
            nums = [c for c in r if isinstance(c, (int, float))]
            if len(nums) >= 2: excel = nums[:2]
            break
    if ws.title in by_sheet:
        e, d, n = by_sheet[ws.title]; lab = 'Caisse (entrées / sorties)'
    else:
        e, d, n = st_sheet[ws.title]; lab = 'Compte ST (travaux / payé)'
    if excel is None:
        tot_rows.append([ws.title, lab, n, None, r3(e), None, r3(d), None, None, 'Pas de ligne TOTAUX trouvée'])
        continue
    de, dd = r3(excel[0] - e), r3(excel[1] - d)
    ok = abs(de) < 0.01 and abs(dd) < 0.01
    tot_rows.append([ws.title, lab, n, r3(excel[0]), r3(e), r3(excel[1]), r3(d), de, dd,
                     'OK' if ok else 'ÉCART : une ligne n’est pas comptée ou le total Excel est faux'])

# ---------------------------------------------------------------- 2. accounts
acc_rows = []
for aid, name in ACC.items():
    ls = sorted([l for l in L if l['acct'] == aid], key=lambda l: (l['sheet'], l['row']))  # Excel order
    e = sum(in_amt(l) for l in ls); d = sum(out_amt(l) for l in ls)
    te = sum(l['e'] for l in ls if l['kind'] == 'transfert'); td = sum(l['d'] for l in ls if l['kind'] == 'transfert')
    bal = 0; low = (0, None)
    for l in ls:
        bal += l['e'] - l['d']
        if bal < low[0]: low = (bal, l['date'])
    cash = sum(l['d'] for l in ls if l['mode'] == 'Espèces')
    nomode = sum(1 for l in ls if l['mode'] in ('Non renseigné', 'NP', '350'))
    acc_rows.append([name, len(ls), r3(e), r3(d), r3(te), r3(td), r3(bal), r3(low[0]), low[1], r3(cash), nomode, None, None])

# ---------------------------------------------------------------- 3. projects x account
proj_rows = []
for p in GRP:
    ls = [l for l in L if l['proj'] == p]
    for aid in ACC:
        s = [l for l in ls if l['acct'] == aid]
        if not s: continue
        proj_rows.append([p, ACC[aid], len(s), r3(sum(in_amt(l) for l in s)), r3(sum(out_amt(l) for l in s)),
                          r3(sum(l['d'] for l in s if l['kind'] == 'transfert')),
                          sum(1 for l in s if l['pc'] == 'Aucune pièce' and out_amt(l) > 0)])
    proj_rows.append([p, 'TOTAL PROJET', len(ls), r3(sum(in_amt(l) for l in ls)), r3(sum(out_amt(l) for l in ls)),
                      r3(sum(l['d'] for l in ls if l['kind'] == 'transfert')),
                      sum(1 for l in ls if l['pc'] == 'Aucune pièce' and out_amt(l) > 0)])

# ---------------------------------------------------------------- 4. articles
art = collections.defaultdict(lambda: dict(n=0, e=0, d=0, cash=0, f=0, b=0, none=0, none_amt=0, accts=set()))
for l in L:
    k = (l['proj'] or '(sans projet)', l['art'])
    a = art[k]; a['n'] += 1; a['e'] += in_amt(l); a['d'] += out_amt(l); a['accts'].add(ACC.get(l['acct'], '?'))
    if l['mode'] == 'Espèces': a['cash'] += out_amt(l)
    if 'Facture' in l['pc']: a['f'] += 1
    if 'Bon' in l['pc'] or 'bon' in l['pc']: a['b'] += 1
    if l['pc'] in ('Aucune pièce', 'Mention sans numéro') and out_amt(l) > 0:
        a['none'] += 1; a['none_amt'] += out_amt(l)
def porder(p): return (GRP.index(p) if p in GRP else 9, p)
art_rows = []
for (p, k), a in sorted(art.items(), key=lambda x: (porder(x[0][0]), -x[1]['d'])):
    pct = max(0.0, min(1.0, 1 - a['none_amt'] / a['d'])) if a['d'] > 0 else None
    art_rows.append([p, k, a['n'], r3(a['e']), r3(a['d']), r3(a['cash']), a['f'], a['b'], a['none'], r3(a['none_amt']),
                     pct, ', '.join(sorted(a['accts']))])

# ---------------------------------------------------------------- 5. aliases
alias = collections.Counter((l['art'], (l['cat'] or '').strip()) for l in L)
by_art = collections.defaultdict(list)
for (a, raw), n in alias.items(): by_art[a].append((raw, n))
alias_rows = []
for a, raws in sorted(by_art.items()):
    if len(raws) > 1 or a == 'Non classé' or 'à préciser' in a:
        for raw, n in sorted(raws, key=lambda x: -x[1]):
            alias_rows.append([a, raw or '(vide)', n, None])

# ---------------------------------------------------------------- 6. ST décompte 90/10
def credit_type(desig):
    p = plain(desig)
    if 'sans retenue' in p: return 'Attachement sans retenue'
    if 'retenue' in p or re.search(r'decompte.*\(10 ?%\)', p): return 'Retenue de garantie créditée'
    if re.search(r'decompte|attachement|attachament|situation|realise', p) or not p: return 'Décompte / attachement'
    if 'tva' in p: return 'TVA'
    if p.startswith('facture'): return 'Facture refacturée'
    if 'paiement mo' in p: return 'Paiement MO (crédit = paiement)'
    return 'Autre crédit'
def fm(x): return f'{x:,.3f}'.replace(',', ' ')
st_rows, trav_rows = [], []
for sid, a in STA.items():
    s = [x for x in data['stLines'] if x['st'] == sid]
    t = collections.defaultdict(float)
    for x in s:
        if x['travaux']:
            ct = credit_type(x['desig']); t[ct] += x['travaux']
            trav_rows.append([a['person'], a['proj'], a['sheet'], x['row'], x['date'], x['desig'], ct, r3(x['travaux'])])
    trav = sum(x['travaux'] for x in s); paye = sum(x['paye'] for x in s)
    dec = t['Décompte / attachement']; rg = t['Retenue de garantie créditée']
    cash = sum(x['paye'] for x in s if x['mode'] and plain(x['mode']).startswith('esp'))
    nonr = sum(x['paye'] for x in s if 'non-retrouve' in x['flags'])
    solde = trav - paye
    rate = (rg / dec) if dec and rg else None
    if trav <= 0.5:
        rgv = 'Aucun travail saisi (paiements seulement)'
    elif dec <= 0.5 and t['Attachement sans retenue'] <= 0.5:
        rgv = 'Compte de paiements (crédit = paiement) : pas de retenue'
    elif rg:
        if abs(rate - 1 / 9) < 0.002: rgv = 'Retenue 10 % déjà créditée au ST (' + fm(rg) + ')'
        elif abs(rate - 5 / 95) < 0.002: rgv = 'Retenue 5 % déjà créditée au ST (' + fm(rg) + ')'
        elif abs(rate - 0.10) < 0.002:
            rgv = ('À VÉRIFIER : retenue = 10 % des décomptes listés. Si les décomptes sont bruts, la retenue est comptée '
                   'deux fois (' + fm(rg) + ' de trop au crédit du ST). Si nets à 90 %, il manque ' + fm(dec / 9 - rg) + '.')
        else:
            rgv = ('À VÉRIFIER : retenue créditée ' + fm(rg) + ' alors que 10 % du brut = ' + fm(dec / 9) +
                   ' (écart ' + fm(rg - dec / 9) + '). Voir « Détail travaux ST ».')
    elif t['Attachement sans retenue'] > 0.5 and dec <= 0.5:
        rgv = 'Attachements « sans retenue » : rien à garder'
    else:
        rgv = 'Retenue pas encore créditée : à payer à la réception ≈ ' + fm(dec / 9) + ' (10 % du brut si décomptes nets à 90 %)'
    if trav > 0.5 and solde < -1: verdict = 'PAYÉ PLUS QUE LE CRÉDIT : ' + fm(-solde) + ' TND de trop'
    elif trav <= 0.5 and paye > 0: verdict = 'Payé ' + fm(paye) + ' sans aucun travail saisi'
    elif solde > 1: verdict = 'SIGAT doit encore ' + fm(solde) + ' TND'
    else: verdict = 'Soldé'
    st_rows.append([a['person'], a['proj'], a['sheet'], len(s), r3(dec), r3(t['Attachement sans retenue']), r3(rg),
                    None if rate is None else rate, r3(t['TVA'] + t['Facture refacturée']), r3(t['Autre crédit'] + t['Paiement MO (crédit = paiement)']),
                    r3(trav), r3(paye), r3(solde), r3(cash), r3(nonr), verdict, rgv, None, None])
st_rows.sort(key=lambda r: (porder(r[1]), r[0]))
trav_rows.sort(key=lambda r: (porder(r[1]), r[0], r[2], r[3]))

# ---------------------------------------------------------------- 7. line lists
def line_row(l, extra=()):
    return [l['proj'], ACC.get(l['acct'], '?'), l['sheet'], l['row'], l['date'], l['art'], l['cat'], l['desig'],
            l['party'], l['mode'], l['bon'], l['fct'], l['pc'], r3(in_amt(l)), r3(out_amt(l)),
            'Transfert' if l['kind'] == 'transfert' else '', ', '.join(FLAG.get(f, f) for f in l['flags']), *extra]
LCOLS = ['Projet', 'Compte / caisse', 'Feuille Excel', 'Ligne Excel', 'Date', 'Article', 'Article (texte Excel)',
         'Désignation', 'Bénéficiaire', 'Paiement', 'Bon N°', 'Facture N°', 'Pièce', 'Entrée', 'Sortie', 'Transfert',
         'Alertes']
LW = [14, 22, 24, 8, 11, 24, 20, 48, 16, 13, 10, 12, 16, 14, 14, 9, 30]

all_rows = [line_row(l, (None, None)) for l in sorted(L, key=lambda l: (porder(l['proj'] or 'zz'), l['sheet'], l['row']))]
nop = [l for l in L if l['proj'] in GRP and out_amt(l) > 0 and l['pc'] in ('Aucune pièce', 'Mention sans numéro')]
nop.sort(key=lambda l: -out_amt(l))
cash = [l for l in L if 'especes' in l['flags']]; cash.sort(key=lambda l: -l['d'])
other = [l for l in L if set(l['flags']) - {'especes'}]
other.sort(key=lambda l: (sorted(set(l['flags']) - {'especes'})[0], l['sheet'], l['row']))
KIND = {a['id']: a['kind'] for a in data['accounts']}
nomode = [l for l in L if l['proj'] in GRP and out_amt(l) > 0 and l['mode'] in ('Non renseigné', 'NP', '350') and KIND.get(l['acct']) != 'chantier']
nomode.sort(key=lambda l: -out_amt(l))
stnr = [x for x in data['stLines'] if x['flags']]

# ---------------------------------------------------------------- 8. problems summary
def amt(ls, f=out_amt): return r3(sum(f(l) for l in ls))
ecarts = [r for r in tot_rows if r[-1].startswith('ÉCART')]
neg = [r for r in acc_rows if r[7] < -1]
over = [r for r in st_rows if r[15].startswith('PAYÉ PLUS') or r[15].startswith('Payé ')]
keep = [r for r in st_rows if r[16].startswith('À VÉRIFIER') or r[16].startswith('Taux')]
noworks = [r for r in st_rows if r[16].startswith('Retenue pas encore')]
unk = [r for r in alias_rows if r[0] == 'Non classé' or 'à préciser' in r[0]]
P = [
 ['Critique', 'Espèces > 5 000 TND', len(cash), amt(cash, lambda l: l['d']),
  'Paiement en espèces de plus de 5 000 TND : amende de 20 % (minimum 2 000 TND) pour SIGAT si contrôle fiscal.',
  'Retrouver la pièce. À l’avenir payer par virement ou chèque.', 'Espèces > 5000'],
 ['Critique', 'Sorties sans pièce (3 projets)', len(nop), amt(nop),
  'Sortie d’argent sans numéro de facture ni de bon. Impossible à justifier en réunion.',
  'Retrouver la facture ou le bon, ou faire signer un reçu.', 'Sans pièce'],
 ['Critique', 'Sous-traitant payé plus que son crédit (travaux)', len(over), r3(sum(-r[12] for r in over)),
  'Le total payé dépasse le total crédité (décomptes, attachements, retenue) dans son compte ST.',
  'Lundi : demander le décompte manquant ou faire reconnaître le trop-payé par le sous-traitant.', 'Sous-traitants 90-10'],
 ['Critique', 'Retenue de garantie mal calculée', len(keep), r3(sum(r[6] for r in keep)),
  'La retenue créditée n’a pas le bon taux (10 % du brut = 11,11 % des décomptes nets ; 5 % du brut = 5,26 %).',
  'Comparer avec le décompte papier : décompte brut ou net ?', 'Sous-traitants 90-10'],
 ['Important', 'Retenue de garantie encore à payer aux ST', len(noworks), r3(sum(r[4] / 9 for r in noworks)),
  'Décomptes sans ligne de retenue : la retenue de 10 % reste chez SIGAT et sera due à la réception définitive.',
  'Noter le montant dans l’accord avec chaque sous-traitant.', 'Sous-traitants 90-10'],
 ['Important', 'Caisse qui passe en négatif', len(neg), None,
  'Le solde cumulé d’une caisse devient négatif : on a dépensé de l’argent qui n’était pas encore dans la caisse.',
  'Il manque une alimentation (entrée) ou une date est fausse.', 'Comptes'],
 ['Important', 'Mode de paiement non renseigné (banques et caisses NGNET, 3 projets)', len(nomode), amt(nomode),
  'On ne sait pas si c’est espèces, virement ou chèque. Impossible de contrôler la règle des 5 000 TND.',
  'Compléter la colonne « Mode de paiement ».', 'Sans mode de paiement'],
 ['Important', 'Paiement à un ST absent de son compte ST', sum(1 for l in L if 'hors-compte' in l['flags']),
  amt([l for l in L if 'hors-compte' in l['flags']]),
  'Payé à un sous-traitant dans une caisse, mais la ligne n’est pas dans sa feuille ST.',
  'Ajouter la ligne dans le compte ST ou corriger le nom.', 'Autres alertes'],
 ['Important', 'Paiement du compte ST non retrouvé dans les caisses', sum(1 for x in stnr if 'non-retrouve' in x['flags']),
  r3(sum(x['paye'] for x in stnr if 'non-retrouve' in x['flags'])),
  'Paiement noté dans la feuille ST mais aucune caisse ne montre ce paiement (même montant, ±10 jours).',
  'Retrouver la caisse qui a payé.', 'ST non retrouvés'],
 ['Important', 'Doublons possibles', sum(1 for l in L if 'doublon' in l['flags']), amt([l for l in L if 'doublon' in l['flags']]),
  'Même jour, même montant, même texte, même projet.', 'Supprimer le doublon ou expliquer.', 'Autres alertes'],
 ['Important', 'Total Excel différent de la somme des lignes', len(ecarts), None,
  'La ligne TOTAUX de la feuille ne correspond pas à la somme des lignes.', 'Vérifier la formule TOTAUX.', 'Contrôle des totaux'],
 ['À vérifier', 'Avance sur bénéfice', sum(1 for l in L if 'benefice' in l['flags']), amt([l for l in L if 'benefice' in l['flags']]),
  'Avance sur bénéfice versée à un ST : à rattacher à son compte.', 'Décider avec le gérant.', 'Autres alertes'],
 ['À vérifier', 'Dates impossibles', sum(1 for l in L if 'date' in l['flags']), amt([l for l in L if 'date' in l['flags']]),
  'Date dans le futur ou avant 2023.', 'Corriger la date.', 'Autres alertes'],
 ['À vérifier', 'Lignes sans date', sum(1 for l in L if 'sans-date' in l['flags']), amt([l for l in L if 'sans-date' in l['flags']]),
  'La ligne n’a pas de date.', 'Compléter la date.', 'Autres alertes'],
 ['À vérifier', 'Article mal écrit ou à préciser', len(unk), None,
  'Article vide, « BC », « RSE » sans capacité, « MO RSE » sans capacité…', 'Choisir le bon article.', 'Articles mal écrits'],
]

# ---------------------------------------------------------------- write
ws = wb.active; ws.title = 'Lisez-moi'
txt = [
 'Contrôle SIGAT — caisses, projets, articles et sous-traitants',
 f'Fait à partir de « {data["meta"]["source"]} » : {len(L)} lignes de caisse, {len(data["stLines"])} lignes de comptes sous-traitants.',
 'Ce classeur ne modifie pas le fichier d’origine. Chaque ligne donne la feuille et le numéro de ligne Excel pour la retrouver.',
 '',
 'Onglets :',
 '• Problèmes : la liste des problèmes, du plus grave au moins grave, avec le nombre de lignes, le montant et l’onglet où les voir.',
 '• Projets : pour Hajeb, Hammed et Ouled Khalfallah, l’argent entré et sorti par compte (UIB SIGAT, UIB NGNET, UIB OMAR, caisses).',
 '• Articles : pour chaque projet et chaque article (RSP, RSE, conduite PEHD, ST…), entrées, sorties, espèces, et combien de sorties sans pièce.',
 '• Comptes : chaque banque et chaque caisse, avec le solde le plus bas atteint et la colonne jaune pour le relevé ou le comptage.',
 '• Sous-traitants 90-10 : décomptes, retenue de garantie (10 % ou 5 %), payé, solde, et si la retenue est bien calculée. « Détail travaux ST » montre chaque décompte.',
 '• Sans pièce / Espèces > 5000 / Sans mode de paiement / Autres alertes / ST non retrouvés : les lignes une par une.',
 '• Toutes les lignes : les 8 520 lignes avec l’article, le type de paiement et la pièce. Utilisez les filtres en haut des colonnes.',
 '• Articles mal écrits : comment le texte Excel a été regroupé en article. Corrigez la colonne jaune si un regroupement est faux.',
 '• Contrôle des totaux : la ligne TOTAUX de chaque feuille comparée à la somme des lignes.',
 '',
 'Règles utilisées :',
 '• Les alimentations de caisse chantier et les virements entre comptes sont des transferts : ils ne comptent pas comme dépense (colonne « Transfert »).',
 '• Pièce = « Facture » si le N° de facture contient un chiffre, « Bon » si le N° de bon contient un chiffre. « S » ou « - » = aucune pièce.',
 '• Espèces > 5 000 TND : strictement plus de 5 000 TND (loi de finances 2026).',
 '• Retenue de garantie : 10 % du brut gardés jusqu’à la réception. Si le décompte est noté net (90 %), la retenue = décompte ÷ 9 (11,11 %). Pour 5 % : décompte × 5/95 (5,26 %).',
 '• Colonnes jaunes = à remplir par vous (Statut OK / KO, note, relevé).',
 'Montants en TND, 3 décimales.']
for i, t in enumerate(txt, 1):
    c = ws.cell(i, 1, t); c.font = Font(name=F, bold=(i == 1), size=13 if i == 1 else 10)
ws.column_dimensions['A'].width = 150

sheet('Problèmes', ['Gravité', 'Problème', 'Lignes', 'Montant (TND)', 'Explication', 'Action', 'Onglet'], P,
      [11, 40, 8, 16, 70, 50, 22], num=['Montant (TND)'], wrap=['Explication', 'Action', 'Problème'], sev_col='Gravité')
w = sheet('Projets', ['Projet', 'Compte / caisse', 'Lignes', 'Entrées', 'Sorties (dépenses)', 'Transferts sortis',
                      'Sorties sans pièce (nb)'], proj_rows, [18, 30, 8, 16, 18, 16, 12],
          num=['Entrées', 'Sorties (dépenses)', 'Transferts sortis'])
for i, r in enumerate(proj_rows, 2):
    if r[1] == 'TOTAL PROJET':
        for j in range(1, 8): w.cell(i, j).font = Font(name=F, bold=True)
w = sheet('Articles', ['Projet', 'Article', 'Lignes', 'Entrées', 'Sorties', 'Dont espèces', 'Avec facture', 'Avec bon',
                       'Sorties sans pièce (nb)', 'Sorties sans pièce (TND)', '% des sorties justifiées', 'Comptes utilisés'],
          art_rows, [16, 30, 8, 15, 15, 14, 9, 9, 10, 15, 11, 50],
          num=['Entrées', 'Sorties', 'Dont espèces', 'Sorties sans pièce (TND)'], wrap=['Comptes utilisés'])
for i in range(2, len(art_rows) + 2): w.cell(i, 11).number_format = '0%'
sheet('Comptes', ['Compte / caisse', 'Lignes', 'Entrées', 'Sorties', 'Transferts reçus', 'Transferts envoyés',
                  'Solde calculé', 'Solde le plus bas', 'Date du plus bas', 'Sorties en espèces',
                  'Lignes sans mode de paiement', 'Relevé / comptage réel', 'Note'], acc_rows,
      [30, 8, 16, 16, 15, 15, 16, 16, 12, 16, 10, 18, 30],
      num=['Entrées', 'Sorties', 'Transferts reçus', 'Transferts envoyés', 'Solde calculé', 'Solde le plus bas',
           'Sorties en espèces'], yellow=['Relevé / comptage réel', 'Note'])
STC = ['Sous-traitant', 'Projet', 'Feuille Excel', 'Lignes', 'Décomptes / attachements', 'Attachements sans retenue',
       'Retenue créditée', 'Retenue ÷ décomptes', 'TVA et factures', 'Autres crédits', 'Total crédit (travaux)', 'Payé',
       'Solde (crédit − payé)', 'Payé en espèces', 'Payé non retrouvé dans les caisses', 'Résultat', 'Retenue de garantie',
       'Validé avec lui (OK / KO)', 'Note réunion']
w = sheet('Sous-traitants 90-10', STC, st_rows, [20, 15, 26, 7, 15, 13, 14, 10, 13, 13, 15, 15, 15, 13, 14, 34, 52, 12, 30],
          num=STC[4:7] + STC[8:15], yellow=['Validé avec lui (OK / KO)', 'Note réunion'], wrap=['Résultat', 'Retenue de garantie'])
for i in range(2, len(st_rows) + 2): w.cell(i, 8).number_format = '0.00%'
dv = DataValidation(type='list', formula1='"OK,KO"', allow_blank=True); w.add_data_validation(dv); dv.add(f'R2:R{len(st_rows) + 1}')
sheet('Détail travaux ST', ['Sous-traitant', 'Projet', 'Feuille Excel', 'Ligne Excel', 'Date', 'Désignation', 'Type de crédit', 'Montant'],
      trav_rows, [20, 15, 26, 8, 11, 60, 30, 15], num=['Montant'], wrap=['Désignation'])

for title, ls in [('Sans pièce', nop), ('Espèces > 5000', cash), ('Sans mode de paiement', nomode), ('Autres alertes', other)]:
    w = sheet(title, LCOLS + ['Statut (OK / KO)', 'Note'], [line_row(l, (None, None)) for l in ls], LW + [10, 30],
              num=['Entrée', 'Sortie'], yellow=['Statut (OK / KO)', 'Note'], wrap=['Désignation'])
    dv = DataValidation(type='list', formula1='"OK,KO"', allow_blank=True); w.add_data_validation(dv); dv.add(f'R2:R{len(ls) + 1}')
sheet('ST non retrouvés', ['Sous-traitant', 'Projet', 'Feuille Excel', 'Ligne Excel', 'Date', 'Désignation', 'Paiement',
                           'Travaux', 'Payé', 'Alertes', 'Statut (OK / KO)', 'Note'],
      [[STA[x['st']]['person'], STA[x['st']]['proj'], x['sheet'], x['row'], x['date'], x['desig'], x['mode'],
        r3(x['travaux']), r3(x['paye']), ', '.join(FLAG.get(f, f) for f in x['flags']), None, None] for x in stnr],
      [22, 16, 28, 8, 11, 50, 12, 14, 14, 30, 10, 30], num=['Travaux', 'Payé'], yellow=['Statut (OK / KO)', 'Note'],
      wrap=['Désignation'])
sheet('Toutes les lignes', LCOLS + ['Statut (OK / KO)', 'Note'], all_rows, LW + [10, 30], num=['Entrée', 'Sortie'],
      yellow=['Statut (OK / KO)', 'Note'])
sheet('Articles mal écrits', ['Article choisi', 'Texte dans l’Excel', 'Lignes', 'Bon article (si faux)'], alias_rows,
      [32, 36, 8, 30], yellow=['Bon article (si faux)'])
sheet('Contrôle des totaux', ['Feuille', 'Type', 'Lignes lues', 'TOTAUX Excel (1)', 'Somme des lignes (1)',
                              'TOTAUX Excel (2)', 'Somme des lignes (2)', 'Écart (1)', 'Écart (2)', 'Résultat'], tot_rows,
      [32, 26, 8, 17, 17, 17, 17, 12, 12, 50],
      num=['TOTAUX Excel (1)', 'Somme des lignes (1)', 'TOTAUX Excel (2)', 'Somme des lignes (2)', 'Écart (1)', 'Écart (2)'])
wb.save(OUT)

# console summary for checking
print('PROBLEMS'); [print('  ', p[0], p[1], p[2], p[3]) for p in P]
print('TOTALS'); [print('  ', r) for r in tot_rows if r[-1] != 'OK']
print('ACCOUNTS'); [print('  ', r[:11]) for r in acc_rows]
print('PROJECTS'); [print('  ', r) for r in proj_rows if r[1] == 'TOTAL PROJET']
print('ST'); [print('  ', r[0], '|', r[1], '|', r[2], '|', r[10], r[11], r[12], '|', r[15], '|', r[16]) for r in st_rows]
json.dump({'articles': art_rows, 'st': st_rows, 'projects': proj_rows, 'problems': P, 'totals': tot_rows, 'accounts': acc_rows},
          open('summary.json', 'w'), ensure_ascii=False, default=str)
