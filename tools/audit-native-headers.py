import argparse
import csv
import hashlib
import re
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('libreoffice_root', type=Path)
args = parser.parse_args()
workspace=Path(__file__).resolve().parents[1]
lo=args.libreoffice_root.resolve()
rows=list(csv.DictReader((workspace/'docs/license/native-linked-source-candidates.tsv').open(encoding='utf-8'),delimiter='\t'))
patterns=[r'SPDX-License-Identifier\s*:\s*[^\r\n*]+',r'(?:GNU\s+)?(?:Lesser\s+)?General Public License[^\r\n*]{0,100}',r'Mozilla Public License[^\r\n*]{0,100}',r'Apache License[^\r\n*]{0,100}',r'(?:MIT|BSD|zlib|IJG)\s+licen[cs]e[^\r\n*]{0,100}',r'\blicen[cs]e\s*:\s*[^\r\n*]{1,100}',r'\bpublic domain\b[^\r\n*]{0,80}',r'(?:Redistribution and use|Permission is hereby granted)[^\r\n*]{0,100}',r'Copyright[^\r\n*]{0,140}']
out=workspace/'docs/license/native-source-header-evidence.tsv'
with out.open('w',encoding='utf-8',newline='') as f:
    writer=csv.writer(f,delimiter='\t',lineterminator='\n')
    writer.writerow(['component','object','source','match','sha256','header_indicators'])
    for row in rows:
        for rel in row['source_candidates'].split(' | ') if row['source_candidates'] else ['']:
            if not rel:
                writer.writerow([row['component'],row['object'],'',row['source_match'],'',''])
                continue
            data=(lo/rel).read_bytes()
            head=data[:12000].decode('utf-8',errors='replace')
            normalized=re.sub(r'\s*\*\s*', ' ', head)
            indicators=[]
            for pattern in patterns:
                indicators.extend(m.group().strip() for m in list(re.finditer(pattern,normalized,re.I))[:5])
            writer.writerow([row['component'],row['object'],rel,row['source_match'],hashlib.sha256(data).hexdigest(),' | '.join(dict.fromkeys(indicators))])
print('completed',len(rows))
