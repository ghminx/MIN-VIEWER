import argparse
import csv
import hashlib
import re
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('target_deps', type=Path)
parser.add_argument('rhwp_root', type=Path)
parser.add_argument('cargo_home', type=Path)
args = parser.parse_args()
workspace = Path(__file__).resolve().parents[1]
rows = list(csv.DictReader((workspace/'docs/license/rhwp-linked-archives.tsv').open(encoding='utf-8'),delimiter='\t'))
patterns = [r'SPDX-License-Identifier\s*:\s*[^\r\n*]+',r'Copyright[^\r\n*]{0,140}',r'(?:GNU\s+)?(?:Lesser\s+)?General Public License[^\r\n*]{0,100}',r'Apache License[^\r\n*]{0,100}',r'Mozilla Public License[^\r\n*]{0,100}',r'(?:MIT|BSD|zlib|IJG)\s+licen[cs]e[^\r\n*]{0,100}',r'\blicen[cs]e\s*:\s*[^\r\n*]{1,100}',r'\bpublic domain\b[^\r\n*]{0,80}',r'Permission is hereby granted[^\r\n*]{0,100}']
seen = set()
missing = []
output = workspace/'docs/license/rhwp-source-header-evidence.tsv'
with output.open('w',encoding='utf-8',newline='') as f:
    writer = csv.writer(f,delimiter='\t',lineterminator='\n')
    writer.writerow(['package','version','source','sha256','header_indicators'])
    for row in rows:
        if row['match'] != 'metadata-and-depfile': continue
        depfile = args.target_deps / (row['crate']+'-'+row['artifact_hash']+'.d')
        first = depfile.read_text(encoding='utf-8',errors='replace').splitlines()[0]
        for source in re.findall(r'(?<!\S)([^\s:]+\.rs)(?=\s|$)', first):
            path = Path(source)
            if not path.is_absolute(): path = args.rhwp_root/path
            path = path.resolve()
            if not (path.is_relative_to(args.rhwp_root.resolve()) or path.is_relative_to(args.cargo_home.resolve())): continue
            key = (row['package'],row['version'],path)
            if key in seen: continue
            seen.add(key)
            if not path.is_file(): missing.append(str(path)); continue
            data = path.read_bytes()
            header = re.sub(r'\s*\*\s*',' ',data[:12000].decode('utf-8',errors='replace'))
            indicators = []
            for pattern in patterns:
                indicators.extend(m.group().strip() for m in list(re.finditer(pattern,header,re.I))[:5])
            origin = 'rhwp/'+path.relative_to(args.rhwp_root.resolve()).as_posix() if path.is_relative_to(args.rhwp_root.resolve()) else 'cargo/'+path.relative_to(args.cargo_home.resolve()).as_posix()
            writer.writerow([row['package'],row['version'],origin,hashlib.sha256(data).hexdigest(),' | '.join(dict.fromkeys(indicators))])
print('source files:',len(seen),'missing:',len(missing))
if missing: raise SystemExit('\n'.join(missing[:10]))
