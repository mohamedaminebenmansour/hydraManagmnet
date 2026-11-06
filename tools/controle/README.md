# tools/controle

Scripts de référence du module « Contrôle de trésorerie ».
Ils ne contiennent aucune donnée : les vrais fichiers restent HORS du dépôt.

| Fichier | Rôle |
| --- | --- |
| convert.py | Lit l'Excel « Caisse des projets » et écrit le JSON d'import HYDRA. |
| analyse.py | Lit ce JSON + l'Excel et écrit le classeur des problèmes. C'est la référence des règles : l'application doit donner les mêmes chiffres. |
| compare.py | Compare le JSON de convert.py avec celui du lecteur Java (mêmes ids, montants, flags, liens). |

Utilisation (Python 3 + openpyxl) :

    python3 tools/controle/convert.py  "<chemin>/Caisse des projets.xlsx"  "<chemin>/SIGAT_import.json"
    python3 tools/controle/analyse.py  "<chemin>/SIGAT_import.json"  "<chemin>/Caisse des projets.xlsx"  "<chemin>/Controle_SIGAT.xlsx"

Vérifier que l'application lit l'Excel exactement comme convert.py (Java 21) :

    cd backend
    ./mvnw -q test -Dtest=ControleExcelDumpTest -Dcontrole.xlsx="<chemin>/Caisse des projets.xlsx" -Dcontrole.out="<chemin>/java.json"
    cd ..
    python3 tools/controle/compare.py  "<chemin>/SIGAT_import.json"  "<chemin>/java.json"

Le résultat attendu est `IDENTIQUE`. Sinon, les 20 premières différences sont affichées.
Utilisez des chemins absolus : le test tourne dans le dossier `backend/`.

Ne jamais mettre dans le dépôt (ni java.json) : le vrai .xlsx, SIGAT_import.json, Controle_SIGAT*.xlsx.
