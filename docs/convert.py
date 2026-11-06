"""Convertit « Caisse des projets.xlsx » en data.json pour l'application Trésorerie SIGAT.
Usage : python3 convert.py <fichier.xlsx> <sortie.json>
Chaque ligne garde sa feuille et son numéro de ligne Excel. L'identifiant d'une ligne
ne dépend pas du numéro de ligne (feuille + date + montants + texte), pour que les
justificatifs restent attachés quand une nouvelle version du fichier arrive."""
import sys, json, re, hashlib, datetime as dt
import openpyxl

SRC, OUT = sys.argv[1], sys.argv[2]
wb = openpyxl.load_workbook(SRC, data_only=True)
TODAY = dt.date.today()

def num(v):
    if v is None or v == '': return 0.0
    if isinstance(v, (int, float)): return float(v)
    try: return float(str(v).replace(' ', '').replace(',', '.'))
    except ValueError: return None

def day(v):
    if isinstance(v, dt.datetime): return v.date().isoformat()
    if isinstance(v, dt.date): return v.isoformat()
    return ''

def txt(v):
    if v is None: return ''
    if isinstance(v, float) and v.is_integer(): v = int(v)
    return re.sub(r'\s+', ' ', str(v)).strip()

def mode_of(m):
    t = txt(m).lower()
    if not t: return 'Non renseigné'
    for k, v in [('esp', 'Espèces'), ('vir', 'Virement'), ('prél', 'Prélèvement'), ('prel', 'Prélèvement'),
                 ('vers', 'Versement'), ('ch', 'Chèque'), ('carte', 'Carte'), ('mand', 'Mandat'), ('mond', 'Mandat'),
                 ('trait', 'Traite'), ('télécharg', 'Téléchargement'), ('telecharg', 'Téléchargement'),
                 ('caisse chantier', 'Caisse chantier'), ('caisse ngnet', 'Caisse NGNET'), ('ngnet', 'NGNET'),
                 ('hani', 'Hani Barkia')]:
        if t.startswith(k): return v
    return txt(m)

PROJ = {'hajeb': 'Hajeb', 'hammed': 'Hammed', 'ouled khalfallah': 'Ouled Khalfallah', 'zaghdoud': 'Zaghdoud',
        'guttar messiouta': 'Guttar Messiouta', 'soualem': 'Soualem', 'sigat': 'SIGAT (frais généraux)',
        'sidi rebeh': 'Sidi Rebeh', 'msalsel': 'Msalsel', 'nebeur': 'Nebeur', 'alaa': 'Alaa'}
def proj_of(v):
    t = txt(v).lower()
    return PROJ.get(t, txt(v).title() if t else 'Sans projet')

PERSONS = [('hamza', 'Hamza Msalmi'), ('ismai', 'Ismaiil Mansouri'), ('ismail', 'Ismaiil Mansouri'), ('hedi', 'Med Hedi El Abed'),
           ('houimli', 'Ahmed Houimli'), ('moula', 'Abd El Moula'), ('aymen', 'Aymen Amri'), ('haider', 'Haider Methneni'),
           ('taher', 'Taher'), ('nejib', 'Nejib'), ('ammar', 'Ammar Hamdi'), ('said', 'Said'), ('moncef', 'Moncef'),
           ('hamed', 'Hamed'), ('aqua', 'Sté Aqua Réseau')]
def person_of(v):
    t = txt(v).lower()
    if not re.match(r'^(st|s\.t|s\.t\.|mo|sous|sté|ste )', t) and 'st ' not in t[:4]: return ''
    for k, p in PERSONS:
        if k in t: return p
    return ''

def kw(t):
    t = t.lower()
    if re.search(r'omar', t): return 'Versements « Omar » (à préciser)'
    if re.search(r'carbur|gazoil|gasoil|essence|recharge', t): return 'Carburant'
    if re.search(r'location|loyer|tractopelle|pelle|camion|voiture|transport|voyage', t): return 'Location et transport'
    if re.search(r'caution|enregistrement|timbre|frais banc|agios|commission|acte de|redressement|recette fiscale|cnss|comptable', t): return 'Cautions, fiscal et frais'
    if re.search(r'salaire|prime|gardien|ouvrier|journ', t): return "Salaires et main-d'œuvre"
    if re.search(r'pr[eê]t', t): return 'Prêts'
    return 'Autres charges'

def poste_out(cat, dest, desig):
    c = txt(cat).lower(); d = txt(dest).lower(); s = txt(desig).lower()
    if 'avance sur benefice' in s or 'avance sur bénéfice' in s: return 'Avance sur bénéfice (ST)'
    for x in (c, d):
        if re.match(r'^(st |s\.t)', x): return 'Sous-traitants'
        if re.match(r'^mo ', x): return "Salaires et main-d'œuvre"
        if 'conduite' in x or 'piéce' in x or 'pièce' in x: return 'Conduites et pièces spéciales'
        if re.match(r'^(rsp|rse|station|surpress|abri|clôture|cloture|bc$|niche|ouvrage|regard|branchement|vanne)', x): return 'Ouvrages (RSP, RSE, station…)'
    if c == 'caisse chantier': return 'Alimentation caisse chantier'
    if d.startswith('projet'): return 'Dépensé pour un autre projet'
    return kw(s)

def nature_in(desig, cat):
    s = txt(desig).lower()
    if 'solde de départ' in s: return 'Solde de départ'
    if 'prêt de' in s: return 'Prêt reçu'
    if 'remboursement prêt' in s or 'regule' in s: return 'Remboursement de prêt'
    if 'caution' in s: return 'Caution récupérée'
    if 'vente camion' in s: return "Vente d'un camion"
    if 'karama' in s: return 'Contrat Karama'
    if 'benefice' in s or 'bénéfice' in s: return 'Bénéfice SIGAT'
    if 'décompte' in s or 'decompte' in s or 'budjet' in s: return 'Décompte client'
    if 'encaissement projet' in s: return 'Encaissement projet'
    if 'alimentation' in s or 'virement' in s or 'versement' in s: return 'Alimentation / virement reçu'
    return 'Autre entrée'

INTERNAL = {606, 607, 962, 963, 1647, 1648}
ACCTS = {'UIB SIGAT': 'uib-sigat', 'UIB OMAR': 'uib-omar', 'UIB NGNET': 'uib-ngnet', 'Caisse SIGAT': 'caisse-sigat'}
accounts = [
    dict(id='uib-sigat', name='UIB SIGAT', kind='banque', owner='SIGAT', project=''),
    dict(id='uib-omar', name='UIB OMAR', kind='banque', owner='SIGAT', project='', note="Compte SIGAT ; « OMAR » est seulement le nom de référence du compte."),
    dict(id='uib-ngnet', name='UIB NGNET', kind='banque', owner='NGNET', project=''),
    dict(id='caisse-sigat', name='Caisse SIGAT', kind='especes', owner='SIGAT', project='', note='À préciser : caisse espèces du bureau ?'),
]
SITE = {'Caisse Chantier Hajeb': ('chantier-hajeb', 'Hajeb'), 'Caisse Chantier Hammed': ('chantier-hammed', 'Hammed'),
        'Caisse Chantier Ouled Khalfalah': ('chantier-okh', 'Ouled Khalfallah'), 'Caisse Chantier Zaghdoud': ('chantier-zaghdoud', 'Zaghdoud')}
PARTNER = {'STE NGNET Hajeb': ('ngnet-hajeb', 'Hajeb', 'NGNET'), 'SOCIETE NGNET HAMMED': ('ngnet-hammed', 'Hammed', 'NGNET'),
           'STE NGNET OULED KHALFALLAH': ('ngnet-okh', 'Ouled Khalfallah', 'NGNET'), 'SOCIETE HANI BARKIA': ('hb-zaghdoud', 'Zaghdoud', 'Hani Barkia')}
for sh, (i, p) in SITE.items(): accounts.append(dict(id=i, name=f'Caisse chantier {p}', kind='chantier', owner='SIGAT', project=p))
for sh, (i, p, o) in PARTNER.items(): accounts.append(dict(id=i, name=f'Caisse {o} {p}', kind='partenaire', owner=o, project=p))

seen = {}
def make_id(sheet, date, e, d, desig):
    base = f'{sheet}|{date}|{e:.3f}|{d:.3f}|{desig.lower()}'
    h = hashlib.sha1(base.encode()).hexdigest()[:10]
    n = seen.get(h, 0); seen[h] = n + 1
    return h if n == 0 else f'{h}-{n}'

lines = []
def header_row(ws, word='Désignation'):
    for r in range(1, 15):
        vals = [txt(ws.cell(r, c).value) for c in range(1, ws.max_column + 1)]
        if any(v.startswith(word) for v in vals): return r, vals
    raise ValueError(ws.title)

def col(vals, *prefixes):
    for p in prefixes:
        for i, v in enumerate(vals):
            if v.lower().startswith(p.lower()): return i + 1
    return None

# --- Caisse Central
ws = wb['Caisse Central']
for r in range(4, ws.max_row + 1):
    g = lambda c: ws.cell(r, c).value
    cr, db = num(g(11)), num(g(12))
    if not (cr or db) and not g(8): continue
    cat, dest, desig = txt(g(6)), txt(g(7)), txt(g(8))
    acct = ACCTS.get(txt(g(10)), '')
    date = day(g(3))
    e, d = cr or 0.0, db or 0.0
    kind = 'transfert' if r in INTERNAL else ('transfert' if cat.lower() == 'caisse chantier' else ('entree' if e and not d else 'sortie'))
    lines.append(dict(id=make_id('Caisse Central', date, e, d, desig), sheet='Caisse Central', row=r, date=date,
        acct=acct, proj=proj_of(dest), e=round(e, 3), d=round(d, 3), mode=mode_of(g(9)), bon=txt(g(4)), fct=txt(g(5)),
        cat=cat, desig=desig, party=person_of(cat), poste=(nature_in(desig, cat) if e and not d else poste_out(cat, '', desig)),
        kind=kind, obs=txt(g(14))))

# --- caisses chantier et partenaires
def load_box(sheet, acct, proj, partner):
    ws = wb[sheet]
    h, vals = header_row(ws)
    cD, cDes, cMode = col(vals, 'Date'), col(vals, 'Désignation'), col(vals, 'Mode')
    cCr, cDb = col(vals, 'Crédi', 'Encaissement'), col(vals, 'Débit', 'Décaissement')
    cDest = col(vals, 'Sous-traitant', 'Déstination', 'Destination')
    for r in range(h + 1, ws.max_row + 1):
        g = lambda c: ws.cell(r, c).value if c else None
        e, d = num(g(cCr)) or 0.0, num(g(cDb)) or 0.0
        desig = txt(g(cDes))
        if not (e or d) and not desig: continue
        dest = txt(g(cDest)); date = day(g(cD))
        p = proj
        if dest.lower().startswith('projet'): p = proj_of(dest[6:].strip()) if not partner else proj
        lines.append(dict(id=make_id(sheet, date, e, d, desig), sheet=sheet, row=r, date=date, acct=acct, proj=p,
            e=round(e, 3), d=round(d, 3), mode=mode_of(g(cMode)), bon='', fct='', cat=dest, desig=desig,
            party=person_of(dest), poste=(('Financement ' + ('NGNET' if 'NGNET' in sheet else 'Hani Barkia') if partner else 'Alimentation reçue') if e and not d else poste_out('', dest, desig)) if (e or d) else '',
            kind=('partenaire' if partner else ('transfert' if e and not d else 'sortie')), obs=''))
for sh, (i, p) in SITE.items(): load_box(sh, i, p, False)
for sh, (i, p, o) in PARTNER.items(): load_box(sh, i, p, True)

# --- comptes sous-traitants
ST_SHEETS = {
 'ST Ismail Hajeb': ('Ismaiil Mansouri', 'Hajeb'), 'S.T Ismaiil Mansouri zagh': ('Ismaiil Mansouri', 'Zaghdoud'),
 'S.T Hamza Msalmi + AMIN Hajeb': ('Hamza Msalmi', 'Hajeb'), 'ST Hamza': ('Hamza Msalmi', 'Hajeb'), 'S.T Hamza Msalmi zagh': ('Hamza Msalmi', 'Zaghdoud'),
 'S.T Med Hedi Abed': ('Med Hedi El Abed', 'Zaghdoud'), 'S.T Ahmed Houimli Hajeb': ('Ahmed Houimli', 'Hajeb'), 'S.T Ahmed Houimli': ('Ahmed Houimli', 'Hammed'),
 'ST Abd El Moula ouled': ('Abd El Moula', 'Ouled Khalfallah'), 'ST Abd El Moula Hajeb': ('Abd El Moula', 'Hajeb'),
 'S.T Aymen  Amri': ('Aymen Amri', 'Hammed'), 'ST Aymen Amri - Soualem': ('Aymen Amri', 'Soualem'), 'ST Haider Methneni': ('Haider Methneni', 'Hajeb'),
 'S.T Taher Zagh': ('Taher', 'Zaghdoud'), 'ST TAHER o,kh ': ('Taher', 'Ouled Khalfallah'), 'st hamed O,KH': ('Hamed', 'Ouled Khalfallah'),
 'ST NEJIB O,KH': ('Nejib', 'Ouled Khalfallah'), 'ST SAID TD O,KH': ('Said', 'Ouled Khalfallah'), 'st said R 500': ('Said', 'Ouled Khalfallah'),
 'ST SAID ABRIS': ('Said', 'Ouled Khalfallah'), 'st said clôture': ('Said', 'Ouled Khalfallah'), 'ST Said BC': ('Said', 'Ouled Khalfallah'),
 'MO RSE 150': ('Équipe MO RSE 150', 'Hajeb'), 'MO RSE 100': ('Équipe MO RSE 100', 'Hajeb'), 'MO RSP Hajeb': ('Équipe MO RSP', 'Hajeb'),
 'MO Station surp': ('Équipe MO station surpresseur', 'Hajeb'), 'MO Hammed': ('Moncef', 'Hammed'), 'MO OULED KHALFALLAH': ('Équipe MO Ouled Khalfallah', 'Ouled Khalfallah'),
 'Sté Aqua Réseau': ('Sté Aqua Réseau', 'Soualem / abris'),
}
stAccounts = []; stLines = []
for sh, (person, proj) in ST_SHEETS.items():
    ws = wb[sh]
    try: h, vals = header_row(ws)
    except ValueError: h, vals = 7, []
    cD, cDes, cMode = col(vals, 'Date'), col(vals, 'Désignation'), col(vals, 'Mode')
    cCr, cDb = col(vals, 'Crédi', 'Encaissement'), col(vals, 'Débit', 'Décaissement')
    sid = 'st-' + re.sub(r'[^a-z0-9]+', '-', sh.lower()).strip('-')
    stAccounts.append(dict(id=sid, sheet=sh, person=person, proj=proj, supplier=sh.startswith('Sté')))
    for r in range(h + 1, ws.max_row + 1):
        g = lambda c: ws.cell(r, c).value if c else None
        w, p = num(g(cCr)) or 0.0, num(g(cDb)) or 0.0
        desig = txt(g(cDes))
        if not (w or p): continue
        date = day(g(cD))
        stLines.append(dict(id=make_id(sh, date, w, p, desig), st=sid, sheet=sh, row=r, date=date, travaux=round(w, 3), paye=round(p, 3),
            mode=mode_of(g(cMode)), desig=desig, link=''))

# --- rapprochement : chaque paiement d'un compte ST retrouvé dans une caisse
def dnum(s):
    try: return dt.date.fromisoformat(s).toordinal()
    except ValueError: return None
byamt = {}
for i, l in enumerate(lines):
    if l['d'] > 0: byamt.setdefault(round(l['d'], 3), []).append(i)
used = set()
acct_proj = {a['id']: a['project'] for a in accounts}
stmap = {a['id']: a for a in stAccounts}
for s in stLines:
    if s['paye'] <= 0: continue
    a = stmap[s['st']]; dn = dnum(s['date'])
    best = None
    for i in byamt.get(round(s['paye'], 3), []):
        if i in used: continue
        l = lines[i]
        if l['proj'] != a['proj'] and acct_proj.get(l['acct']) != a['proj']: continue
        ln = dnum(l['date'])
        gap = abs(ln - dn) if (ln and dn) else 999
        if gap > 10 and not (gap == 999): continue
        score = gap + (0 if l['party'] == a['person'] else 20)
        if best is None or score < best[0]: best = (score, i)
    if best:
        used.add(best[1]); s['link'] = lines[best[1]]['id']; lines[best[1]].setdefault('stLink', s['id'])

# --- contrôles automatiques par ligne
for l in lines:
    f = []
    if l['mode'] == 'Espèces' and l['d'] > 5000 and l['kind'] != 'transfert': f.append('especes')
    if not l['date']: f.append('sans-date')
    elif l['date'] > TODAY.isoformat() or l['date'] < '2023-01-01': f.append('date')
    if l['party'] and l['d'] > 0 and 'stLink' not in l and l['kind'] == 'sortie': f.append('hors-compte')
    if l['poste'] == 'Avance sur bénéfice (ST)': f.append('benefice')
    if l['kind'] == 'transfert' and l['row'] in INTERNAL: f.append('interne')
    l['flags'] = f
key = {}
for l in lines:
    k = (l['sheet'], l['date'], l['e'], l['d'], l['desig'].lower(), l['proj'], l['acct'])
    key.setdefault(k, []).append(l)
for k, g in key.items():
    if len(g) > 1 and k[0] in ('Caisse Central',) + tuple(SITE) and not re.match(r'^(cnss|agrement|prime|pour omar|gazoil|voiture omar)', k[4]) and (k[2] or k[3]):
        for l in g[1:]: l['flags'].append('doublon')
for s in stLines:
    s['flags'] = []
    if s['paye'] > 0 and not s['link']: s['flags'].append('non-retrouve')
    if s['date'] and (s['date'] > TODAY.isoformat() or s['date'] < '2023-01-01'): s['flags'].append('date')

data = dict(meta=dict(source=SRC.split('/')[-1], generated=dt.datetime.now().isoformat(timespec='minutes'),
                      lines=len(lines), stLines=len(stLines)),
            accounts=accounts, stAccounts=stAccounts, lines=lines, stLines=stLines)
json.dump(data, open(OUT, 'w'), ensure_ascii=False, separators=(',', ':'))
print('lines', len(lines), 'stLines', len(stLines), 'linked', sum(1 for s in stLines if s['link']),
      'payments', sum(1 for s in stLines if s['paye'] > 0))
