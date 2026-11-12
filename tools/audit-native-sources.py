import argparse
import csv
import hashlib
import re
import tarfile
import zipfile
from collections import defaultdict
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('libreoffice_root', type=Path)
args = parser.parse_args()
workspace=Path(__file__).resolve().parents[1]
lo=args.libreoffice_root.resolve()
tarballs=lo/'external/tarballs'
sources=list(csv.DictReader((workspace/'docs/license/native-linked-source-candidates.tsv').open(encoding='utf-8'),delimiter='\t'))
mapping=list(csv.DictReader((workspace/'docs/license/native-source-map.tsv').open(encoding='utf-8'),delimiter='\t'))
archives={row['component']:re.findall(r'=([^ |]+)',row['source_archives']) for row in mapping}
wanted=defaultdict(list)
for row in sources:
    if row['source_match'] not in ('unique','archive_directory','build_definition','object_directory','build_log','multiple_confirmed'): continue
    for candidate in row['source_candidates'].split(' | '):
        rel=Path(candidate)
        subpath='/'.join(rel.parts[3:])
        wanted[row['component']].append((row,subpath,hashlib.sha256((lo/rel).read_bytes()).hexdigest()))
results=[]
for component, entries in wanted.items():
    candidates=archives[component]
    names=set(sub for _,sub,_ in entries)
    contents=defaultdict(list)
    for archive in candidates:
        path=tarballs/archive
        if zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as z:
                for member in z.infolist():
                    parts=Path(member.filename).parts
                    for candidate in (member.filename, '/'.join(parts[1:]), 'a4/'+member.filename):
                        if candidate in names and not member.is_dir():
                            contents[candidate].append((archive,hashlib.sha256(z.read(member)).hexdigest()))
        else:
            with tarfile.open(path) as t:
                for member in t:
                    if not member.isfile(): continue
                    parts=Path(member.name).parts
                    for candidate in (member.name,'/'.join(parts[1:])):
                        if candidate in names:
                            contents[candidate].append((archive,hashlib.sha256(t.extractfile(member).read()).hexdigest()))
    for row,subpath,digest in entries:
        hits=contents[subpath]
        status='same' if any(d==digest for _,d in hits) else ('different' if hits else 'not_found')
        results.append([component,row['object'],'workdir/UnpackedTarball/'+component+'/'+subpath,status,' | '.join(a for a,_ in hits),digest])
    print(component, len(entries), sum(r[3]=='same' for r in results if r[0]==component), flush=True)
out=workspace/'docs/license/native-source-comparison.tsv'
with out.open('w',encoding='utf-8',newline='') as f:
    w=csv.writer(f,delimiter='\t',lineterminator='\n');w.writerow(['component','object','source_candidate','comparison','source_archives','built_source_sha256']);w.writerows(results)
