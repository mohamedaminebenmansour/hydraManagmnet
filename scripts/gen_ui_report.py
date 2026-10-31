"""HYDRA UI Research Report — page audit + dual view decisions."""
import os, sys, hashlib
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib.colors import HexColor, white
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.enums import TA_LEFT, TA_CENTER, TA_JUSTIFY
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak, Table, TableStyle, KeepTogether
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfbase.pdfmetrics import registerFontFamily

FONT_DIR = "/usr/share/fonts/truetype"
try:
    pdfmetrics.registerFont(TTFont('NotoSans', f'{FONT_DIR}/chinese/NotoSansSC-Regular.ttf'))
    pdfmetrics.registerFont(TTFont('NotoSans-Bold', f'{FONT_DIR}/chinese/NotoSansSC-Bold.ttf'))
    pdfmetrics.registerFont(TTFont('NotoSerif', f'{FONT_DIR}/noto-serif-sc/NotoSerifSC-Regular.ttf'))
    pdfmetrics.registerFont(TTFont('NotoSerif-Bold', f'{FONT_DIR}/noto-serif-sc/NotoSerifSC-Bold.ttf'))
    registerFontFamily('NotoSans', normal='NotoSans', bold='NotoSans-Bold')
    registerFontFamily('NotoSerif', normal='NotoSerif', bold='NotoSerif-Bold')
    FB, FH, FBOLD = 'NotoSerif', 'NotoSans', 'NotoSans-Bold'
except:
    FB, FH, FBOLD = 'Helvetica', 'Helvetica-Bold', 'Helvetica-Bold'

C_PRIMARY = HexColor('#0d9488'); C_ACCENT = HexColor('#0891b2')
C_BG = HexColor('#f8fafc'); C_TEXT = HexColor('#0f172a'); C_MUTED = HexColor('#64748b')
C_BORDER = HexColor('#e2e8f0'); C_GREEN = HexColor('#16a34a'); C_RED = HexColor('#dc2626')
C_AMBER = HexColor('#d97706')
PAGE_W, PAGE_H = A4; ML = MR = 22*mm; MT = MB = 22*mm; CW = PAGE_W - ML - MR

styles = getSampleStyleSheet()
s_title = ParagraphStyle('T', parent=styles['Title'], fontName=FBOLD, fontSize=26, textColor=C_PRIMARY, alignment=TA_LEFT, spaceAfter=6, leading=32)
s_sub = ParagraphStyle('S', parent=styles['Normal'], fontName=FB, fontSize=13, textColor=C_MUTED, spaceAfter=20, leading=18)
s_h1 = ParagraphStyle('H1', parent=styles['Heading1'], fontName=FBOLD, fontSize=18, textColor=C_PRIMARY, spaceBefore=22, spaceAfter=8, leading=24)
s_h2 = ParagraphStyle('H2', parent=styles['Heading2'], fontName=FBOLD, fontSize=14, textColor=C_TEXT, spaceBefore=16, spaceAfter=6, leading=19)
s_h3 = ParagraphStyle('H3', parent=styles['Heading3'], fontName=FBOLD, fontSize=11, textColor=C_ACCENT, spaceBefore=10, spaceAfter=4, leading=15)
s_body = ParagraphStyle('B', parent=styles['Normal'], fontName=FB, fontSize=10.5, textColor=C_TEXT, alignment=TA_JUSTIFY, spaceAfter=6, leading=16)
s_callout = ParagraphStyle('C', parent=s_body, fontSize=10, leftIndent=12, rightIndent=12, spaceBefore=6, spaceAfter=6, backColor=C_BG, borderColor=C_BORDER, borderWidth=1, borderPadding=8, leading=15)

def on_page(c, d):
    c.saveState(); c.setFont(FB, 8); c.setFillColor(C_MUTED)
    c.drawString(ML, 12*mm, "HYDRA BTP — Recherche UX: Audit des Pages")
    c.drawRightString(PAGE_W - MR, 12*mm, f"Page {d.page}")
    c.setStrokeColor(C_BORDER); c.setLineWidth(0.5); c.line(ML, 14*mm, PAGE_W - MR, 14*mm)
    c.restoreState()

story = []
# COVER
story.append(Spacer(1, 35*mm))
story.append(Paragraph('<font name="%s" size="14" color="#0d9488">● HYDRA BTP</font>' % FBOLD, ParagraphStyle('b', fontSize=14, textColor=C_PRIMARY, spaceAfter=16)))
story.append(Paragraph('Audit UX des Pages + Dual View', s_title))
story.append(Paragraph('Recherche experte: navigation, simplicite, et decisions table/cartes par page', s_sub))
story.append(PageBreak())

# 1. RESEARCH FINDINGS
story.append(Paragraph('1. Recherche: comment les grands logiciels gerent leurs pages', s_h1))
story.append(Paragraph(
    'Apres recherche approfondie sur Procore (1.5M utilisateurs BTP), Fieldwire (Hilti), '
    'Linear, Notion, Stripe, Facebook/Instagram, et les ERP BTP (Acumatica, Sage, CMiC), '
    'voici les 5 principes universels qui emergent.', s_body))

story.append(Paragraph('1.1 Procore: deux niveaux (Company + Project)', s_h2))
story.append(Paragraph(
    'Procore separe strictement Company level (admin, users, config) de Project level '
    '(daily work: drawings, RFIs, financials, daily logs). Chaque niveau a ses propres '
    '"tools" dans la barre laterale. Le niveau Project est celui que 90% des utilisateurs '
    'voient quotidiennement. Lesson: HYDRA devrait faire pareil — la page d\'accueil du '
    'chantier est le hub quotidien, pas la page d\'admin.', s_body))

story.append(Paragraph('1.2 Facebook/Instagram: la simplicite par reduction', s_h2))
story.append(Paragraph(
    'Instagram a reussi la ou Facebook a echoue: simplicite radicale. Instagram a 5 onglets '
    '(Accueil, Recherche, Creer, Activite, Profil). Facebook en a 15+. Plus de tabs = plus de '
    'charge cognitive = moins d\'adoption. Lesson: HYDRA doit reduire, pas ajouter. '
    'Chaque page du menu doit repondre a un besoin precis en 1 mot.', s_body))

story.append(Paragraph('1.3 Linear: vitesse + clavier + minimalisme', s_h2))
story.append(Paragraph(
    'Linear (500K+ utilisateurs) a bati son succes sur 3 principes: (1) interface minimaliste '
    'sans clutter, (2) tout est accessible au clavier, (3) chaque action prend < 1 seconde. '
    'Pas de sidebars multiples, pas de popups, pas de tabs cachees. Lesson: HYDRA doit '
    'reduire le nombre de clics, pas l\'augmenter.', s_body))

story.append(Paragraph('1.4 Stripe: donnees financieres = clarte par typographie', s_h2))
story.append(Paragraph(
    'Stripe dashboard utilise la typographie (pas la couleur) pour la hierarchie. '
    'Les montants sont en gros, gras, avec tabular-nums. Les labels sont petits et gris. '
    'Le whitespace fait le travail visuel, pas les bordures. Lesson: HYDRA doit faire '
    'pareil — gros montants, petits labels, whitespace generaux.', s_body))

story.append(Paragraph(
    '<b>5 principes universels retenus:</b><br/>'
    '1. Default = vue dense (table) pour les listes de donnees<br/>'
    '2. Maximum 7 items dans le menu principal<br/>'
    '3. Une page = un objectif (pas de multi-tabs qui dispersent)<br/>'
    '4. Gros montants, petits labels, whitespace<br/>'
    '5. Toggle de vue persistant (localStorage)', s_callout))
story.append(PageBreak())

# 2. PAGE-BY-PAGE AUDIT
story.append(Paragraph('2. Audit page par page: dual view necessaire?', s_h1))
story.append(Paragraph(
    'Chaque page du menu HYDRA a ete analysee: type de contenu, frequence d\'usage, '
    'besoin de scan rapide, et decision sur le dual view.', s_body))

audit_data = [
    ['Page', 'Type', 'Dual View?', 'Raison'],
    ['Accueil', 'Dashboard', 'NON', 'Cartes de stats + raccourcis. Pas une liste de donnees.'],
    ['Mes taches', 'Inbox', 'NON (cards)', 'Action-oriented. Chaque carte = une decision. Table perd le contexte.'],
    ['Tresorerie (hub)', 'Navigation', 'NON', '3 cartes (caisses, sous-traitants, nouvelle operation). Hub, pas liste.'],
    ['Espace Finance', 'Dashboard', 'NON', 'Cartes de caisses + pending + export. Dashboard, pas liste.'],
    ['Caisses (pot list)', 'Liste', 'OUI', 'Liste de caisses. Table = nom, type, solde, statut. Cards actuelles OK en secondaire.'],
    ['Caisse detail', 'Liste + table', 'OUI (fait)', 'Historique des operations. Tableau deja implemente (commit ebc739a).'],
    ['Sous-traitants', 'Liste', 'OUI', 'Liste de tiers. Table = nom, type, telephone, total paye.'],
    ['Operations (detail)', 'Fiche', 'NON', 'Detail d\'une operation. Pas une liste.'],
    ['Operations (form)', 'Formulaire', 'NON', 'Creation/modification. Pas une liste.'],
    ['Validation (desk)', 'Decision', 'NON', 'Dossier d\'approbation. Pas une liste. Do NOT table-ify.'],
    ['Processus (hub)', 'Navigation', 'NON', 'Hub de lancement. Pas une liste.'],
    ['Chantiers (list)', 'Liste', 'OUI', 'Liste de projets. Table = nom, type, statut, budget.'],
    ['Chantier detail', 'Tabs', 'NON', 'Onglets (Dossiers, Equipe, Processus). Pas une liste simple.'],
    ['Taches/Signalements', 'Liste', 'OUI', 'Liste de tickets. Table = titre, statut, assigne, date.'],
    ['Documents (list)', 'Liste', 'OUI', 'Liste de documents. Table = reference, titre, type, statut.'],
    ['Utilisateurs', 'Liste', 'OUI', 'Liste d\'utilisateurs. Table = nom, email, role, statut.'],
    ['Roles', 'Liste', 'NON', 'Faible volume (5-10 roles). Cards suffisent.'],
    ['Journal (audit)', 'Liste', 'NON', 'Log chronologique. Pas besoin de toggle — table naturelle.'],
]
a_table = Table(audit_data, colWidths=[35*mm, 22*mm, 20*mm, CW - 77*mm])
a_table.setStyle(TableStyle([
    ('BACKGROUND', (0,0), (-1,0), C_PRIMARY),
    ('TEXTCOLOR', (0,0), (-1,0), white),
    ('FONT', (0,0), (-1,0), FBOLD, 9),
    ('FONT', (0,1), (-1,-1), FB, 8),
    ('TEXTCOLOR', (0,1), (-1,-1), C_TEXT),
    ('VALIGN', (0,0), (-1,-1), 'TOP'),
    ('ROWBACKGROUNDS', (0,1), (-1,-1), [white, C_BG]),
    ('GRID', (0,0), (-1,-1), 0.5, C_BORDER),
    ('LEFTPADDING', (0,0), (-1,-1), 5),
    ('RIGHTPADDING', (0,0), (-1,-1), 5),
    ('TOPPADDING', (0,0), (-1,-1), 4),
    ('BOTTOMPADDING', (0,0), (-1,-1), 4),
]))
story.append(a_table)
story.append(Spacer(1, 10))

story.append(Paragraph(
    '<b>Resume: 6 pages sur 18 ont besoin du dual view.</b> '
    'Les 12 autres sont des dashboards, formulaires, hubs, ou fiches detail '
    'ou le table n\'apporte rien. Ne pas ajouter de toggle sur ces pages — '
    'cela ajouterait de la confusion sans valeur.', s_callout))
story.append(PageBreak())

# 3. PRIORITY ORDER
story.append(Paragraph('3. Ordre de priorite pour le dual view', s_h1))
story.append(Paragraph(
    'Les 6 pages qui ont besoin du dual view, dans l\'ordre d\'impact:', s_body))

prio_data = [
    ['#', 'Page', 'Key', 'Default', 'Colonnes table', 'Impact'],
    ['1', 'Caisse detail\n(historique)', 'movements', 'table', 'Date, Libelle, Tiers, Type, Mode, Montant, Statut', 'FAIT (commit ebc739a)'],
    ['2', 'Sous-traitants', 'parties', 'table', 'Nom, Type, Telephone, Total paye, Dernier paiement', 'HAUT — liste longue'],
    ['3', 'Chantiers', 'projects', 'table', 'Nom, Type, Statut, Solde caisse, Membres', 'HAUT — vue d\'ensemble'],
    ['4', 'Taches/Signalements', 'tickets', 'table', 'Titre, Statut, Assigne, Projet, Date', 'MOYEN — volume modere'],
    ['5', 'Documents', 'documents', 'table', 'Reference, Titre, Type, Dossier, Statut', 'MOYEN — volume modere'],
    ['6', 'Utilisateurs', 'users', 'table', 'Nom, Email, Role, Actif', 'BAS — faible volume'],
]
p_table = Table(prio_data, colWidths=[8*mm, 30*mm, 20*mm, 15*mm, 60*mm, CW - 133*mm])
p_table.setStyle(TableStyle([
    ('BACKGROUND', (0,0), (-1,0), C_PRIMARY),
    ('TEXTCOLOR', (0,0), (-1,0), white),
    ('FONT', (0,0), (-1,0), FBOLD, 8),
    ('FONT', (0,1), (-1,-1), FB, 8),
    ('TEXTCOLOR', (0,1), (-1,-1), C_TEXT),
    ('VALIGN', (0,0), (-1,-1), 'TOP'),
    ('ROWBACKGROUNDS', (0,1), (-1,-1), [white, C_BG]),
    ('GRID', (0,0), (-1,-1), 0.5, C_BORDER),
    ('LEFTPADDING', (0,0), (-1,-1), 4),
    ('RIGHTPADDING', (0,0), (-1,-1), 4),
    ('TOPPADDING', (0,0), (-1,-1), 4),
    ('BOTTOMPADDING', (0,0), (-1,-1), 4),
]))
story.append(p_table)
story.append(Spacer(1, 12))

# 4. WHAT TO CHANGE + WHY
story.append(Paragraph('4. Ce qu\'on change et pourquoi', s_h1))

story.append(Paragraph('4.1 Caisses (pot list) — ajouter dual view', s_h2))
story.append(Paragraph(
    'Actuellement: cartes uniquement. Pourquoi table: un gérant avec 9 caisses '
    '(1 centrale + 8 chantiers) veut comparer les soldes en un coup d\'œil. Une table '
    'triée par solde permet de voir quelle caisse est presque à sec. Les cartes '
    'nécessitent de scroller.', s_body))

story.append(Paragraph('4.2 Sous-traitants (party list) — ajouter dual view', s_h2))
story.append(Paragraph(
    'Actuellement: cartes uniquement. Pourquoi table: 10+ sous-traitants, on veut '
    'voir le total payé à chacun trié par montant. Une table avec colonne "Total payé" '
    'permet de comparer. Les cartes montrent le total mais pas côte à côte.', s_body))

story.append(Paragraph('4.3 Chantiers (projects list) — ajouter dual view', s_h2))
story.append(Paragraph(
    'Actuellement: grille de cartes. Pourquoi table: 9 projets, on veut comparer '
    'statut + solde caisse + nombre de membres. Table = vue comparative. Cards = '
    'vue visuelle par projet.', s_body))

story.append(Paragraph('4.4 Tâches/Signalements — ajouter dual view', s_h2))
story.append(Paragraph(
    'Actuellement: liste de cartes. Pourquoi table: les tickets sont des items '
    'structurés (titre, statut, assigné, date). Une table permet de trier par '
    'statut ou assigné. Cards restent utiles pour le scan visuel.', s_body))

story.append(Paragraph('4.5 Documents — ajouter dual view', s_h2))
story.append(Paragraph(
    'Actuellement: grille/grille. Pourquoi table: les documents ont une reference, '
    'un type, un dossier — des champs naturels pour une table. Cards restent pour '
    'la vue visuelle (aperçu).', s_body))

story.append(Paragraph('4.6 Utilisateurs — ajouter dual view', s_h2))
story.append(Paragraph(
    'Actuellement: liste. Pourquoi table: les utilisateurs ont email + role + statut '
    'qui sont parfaits pour une table. Faible volume (5-15) donc cards OK aussi.', s_body))

story.append(Paragraph('4.7 Pages qui NE changent PAS', s_h2))
story.append(Paragraph(
    'Les pages suivantes ne reçoivent PAS de dual view, avec raison:', s_body))
no_change = [
    ['Page', 'Raison de ne PAS changer'],
    ['Accueil', 'Dashboard de stats. Pas une liste de donnees.'],
    ['Mes taches', 'Action-oriented. Table perd le contexte de decision.'],
    ['Tresorerie hub', 'Navigation. 3 cartes. Pas une liste.'],
    ['Espace Finance', 'Dashboard. Cartes de caisses + pending.'],
    ['Operations detail', 'Fiche detail. Pas une liste.'],
    ['Operations form', 'Formulaire. Pas une liste.'],
    ['Validation desk', 'Dossier d\'approbation. Do NOT table-ify.'],
    ['Processus hub', 'Navigation + lancement.'],
    ['Chantier detail', 'Tabs (Dossiers, Equipe, Processus). Pas une liste simple.'],
    ['Roles', '5-10 items. Cards suffisent.'],
    ['Journal', 'Log chronologique. Table naturelle, pas besoin de toggle.'],
]
nc_table = Table(no_change, colWidths=[35*mm, CW - 35*mm])
nc_table.setStyle(TableStyle([
    ('BACKGROUND', (0,0), (-1,0), C_AMBER),
    ('TEXTCOLOR', (0,0), (-1,0), white),
    ('FONT', (0,0), (-1,0), FBOLD, 9),
    ('FONT', (0,1), (-1,-1), FB, 8.5),
    ('TEXTCOLOR', (0,1), (-1,-1), C_TEXT),
    ('VALIGN', (0,0), (-1,-1), 'TOP'),
    ('ROWBACKGROUNDS', (0,1), (-1,-1), [white, C_BG]),
    ('GRID', (0,0), (-1,-1), 0.5, C_BORDER),
    ('LEFTPADDING', (0,0), (-1,-1), 5),
    ('RIGHTPADDING', (0,0), (-1,-1), 5),
    ('TOPPADDING', (0,0), (-1,-1), 4),
    ('BOTTOMPADDING', (0,0), (-1,-1), 4),
]))
story.append(nc_table)
story.append(PageBreak())

# 5. CONCLUSION
story.append(Paragraph('5. Conclusion', s_h1))
story.append(Paragraph(
    'Le dual view (table + cards) est un pattern puissant pour les pages de liste '
    'où l\'utilisateur scanne, compare, et trie. Mais appliqué aveuglément à toutes '
    'les pages, il devient du bruit. La règle: <b>si la page affiche une liste de '
    'données structurées (plus de 5 items) que l\'utilisateur trie ou compare → table.</b> '
    'Si la page est un dashboard, un hub, un formulaire, ou une fiche detail → '
    'pas de toggle, pas de table.', s_body))

story.append(Paragraph(
    'Le travail restant: appliquer le pattern ViewModeService aux 5 pages identifiées '
    '(parties, projects, tickets, documents, users). Le pattern est identique à celui '
    'déjà implémenté sur le pot-detail (commit ebc739a): ViewModeService.forPage(key) + '
    'toggle + table + cards. Chaque page prend 30 minutes.', s_callout))

# BUILD
output = '/home/z/my-project/download/HYDRA_UI_Audit_Dual_View.pdf'
os.makedirs(os.path.dirname(output), exist_ok=True)
doc = SimpleDocTemplate(output, pagesize=A4, leftMargin=ML, rightMargin=MR, topMargin=MT, bottomMargin=MB,
    title='HYDRA BTP — Audit UX: Pages + Dual View', author='Z.ai')
doc.build(story, onFirstPage=lambda c,d: None, onLaterPages=on_page)
print(f"OK: {output} ({os.path.getsize(output)/1024:.1f} KB)")
