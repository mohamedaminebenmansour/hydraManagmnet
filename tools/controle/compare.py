"""Compare deux exports : celui de convert.py et celui du lecteur Java (ControleExcelDumpTest).
Usage : python3 compare.py py.json java.json
Affiche IDENTIQUE, ou les 20 premières différences. Une clé absente vaut null."""
import sys, json

a, b = (json.load(open(p, encoding='utf-8')) for p in sys.argv[1:3])
diffs = []

def cmp_list(name, la, lb):
    if len(la) != len(lb):
        diffs.append(f'{name} : {len(la)} contre {len(lb)}')
    for i, (x, y) in enumerate(zip(la, lb)):
        for k in sorted(set(x) | set(y)):
            if x.get(k) != y.get(k):
                diffs.append(f'{name}[{i}] id={x.get("id")} {k} : {x.get(k)!r} contre {y.get(k)!r}')

for n in ('lines', 'stLines'):
    if a['meta'][n] != b['meta'][n]:
        diffs.append(f'meta.{n} : {a["meta"][n]} contre {b["meta"][n]}')
for n in ('accounts', 'stAccounts', 'lines', 'stLines'):
    cmp_list(n, a[n], b[n])
cmp_list('recap', a.get('recap') or [], b.get('recap') or [])

print('lines', len(a['lines']), len(b['lines']), 'stLines', len(a['stLines']), len(b['stLines']),
      'recap', len(a.get('recap') or []), len(b.get('recap') or []))
if diffs:
    print(len(diffs), 'différence(s) :')
    for d in diffs[:20]: print(' ', d)
    sys.exit(1)
print('IDENTIQUE')
