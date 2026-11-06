# tools/controle

Scripts de référence du module « Contrôle de trésorerie ».
Ils ne contiennent aucune donnée : les vrais fichiers restent HORS du dépôt.

| Fichier | Rôle |
| --- | --- |
| convert.py | Lit l'Excel « Caisse des projets » et écrit le JSON d'import HYDRA. |
| analyse.py | Lit ce JSON + l'Excel et écrit le classeur des problèmes. C'est la référence des règles : l'application doit donner les mêmes chiffres. |

Utilisation (Python 3 + openpyxl) :

    python3 tools/controle/convert.py  "<chemin>/Caisse des projets.xlsx"  "<chemin>/SIGAT_import.json"
    python3 tools/controle/analyse.py  "<chemin>/SIGAT_import.json"  "<chemin>/Caisse des projets.xlsx"  "<chemin>/Controle_SIGAT.xlsx"

Ne jamais mettre dans le dépôt : le vrai .xlsx, SIGAT_import.json, Controle_SIGAT*.xlsx.
