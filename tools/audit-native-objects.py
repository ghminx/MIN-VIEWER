import argparse
import csv
import os
import re
from collections import defaultdict
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('libreoffice_root', type=Path)
parser.add_argument('link_map', type=Path)
args = parser.parse_args()
workspace = Path(__file__).resolve().parents[1]
lo = args.libreoffice_root.resolve()
mapfile = args.link_map.resolve()
mapped = {}
observed_symbols = defaultdict(set)
direct = set()
with (workspace / 'docs/license/native-source-map.tsv').open(encoding='utf-8') as f:
    direct = {row['component'] for row in csv.DictReader(f, delimiter='\t')}
static = {name: name for name in ['afdko','expat','graphite','md4c','zxcvbn-c','zxing']}
static.update({'libjpeg-turbo':'libjpeg-turbo','libpng':'libpng','boost_iostreams':'boost','boost_locale':'boost'})
pattern = re.compile(r'workdir/(UnpackedTarball/([^/]+)/[^\s]+?|LinkTarget/StaticLibrary/lib([^/]+?))\.a\(([^()]+\.(?:o|ao))\):\(\.(?:text|rodata|data|bss)(?:\.|\))')
with mapfile.open(encoding='utf-8', errors='replace') as f:
    for line in f:
        if '.a(' not in line: continue
        match = pattern.search(line)
        if not match: continue
        component = match.group(2) if match.group(2) else (static.get(match.group(3)) or static.get(match.group(3).removeprefix('lib')))
        if component not in direct: continue
        mapped[(component,match.group(1)+'.a',match.group(4))] = True
        if component == 'afdko' and match.group(4) in ('cffread.o', 'pstoken.o'):
            symbol = re.search(r':\(\.text\.([^\)]+)\)', line)
            if symbol: observed_symbols[match.group(4)].add(symbol.group(1))
wanted = defaultdict(set)
for component, archive, obj in mapped:
    name = re.sub(r'\.(?:o|ao)$', '', obj)
    name = re.sub(r'\.(?:c|cc|cpp|cxx|s|asm)$', '', name, flags=re.I)
    if '_la-' in name: name = name.split('_la-',1)[1]
    if '-lib-' in name: name = name.split('-lib-',1)[1]
    wanted[component].add(name)
index = defaultdict(list)
for component, stems in wanted.items():
    base = lo / 'workdir/UnpackedTarball' / component
    if not base.exists(): continue
    for root, dirs, files in os.walk(base):
        dirs[:] = [d for d in dirs if d not in {'.git','.deps','.libs','builddir','node_modules','test','tests','gtests','examples','doc','docs'}]
        for file in files:
            path = Path(file)
            if path.suffix.lower() not in {'.c','.cc','.cpp','.cxx','.s','.asm'}: continue
            if path.stem in stems:
                index[(component,path.stem)].append(Path(root)/file)
out = workspace / 'docs/license/native-linked-source-candidates.tsv'
with out.open('w',encoding='utf-8',newline='') as f:
    writer=csv.writer(f,delimiter='\t',lineterminator='\n')
    writer.writerow(['component','archive','object','source_candidates','source_match'])
    for component, archive, obj in sorted(mapped):
        stem=re.sub(r'\.(?:o|ao)$', '', obj).split('_la-',1)[-1].split('-lib-',1)[-1]
        stem=re.sub(r'\.(?:c|cc|cpp|cxx|s|asm)$', '', stem, flags=re.I)
        candidates=index[(component,stem)]
        if component == 'harfbuzz' and obj == 'OT_Var_VARC_VARC.cc.o':
            candidates=[lo/'workdir/UnpackedTarball/harfbuzz/src/OT/Var/VARC/VARC.cc']
        basis = 'unique' if len(candidates) == 1 else ('ambiguous' if candidates else 'unmatched')
        if len(candidates)>1 and archive.startswith('UnpackedTarball/'):
            parent=(lo/'workdir'/archive).parent
            if parent.name == '.libs': parent=parent.parent
            matches=[p for p in candidates if p.parent == parent]
            if len(matches)==1: candidates=matches; basis='archive_directory'
        if len(candidates)>1 and component=='afdko':
            mk=(lo/'external/afdko/StaticLibrary_afdko.mk').read_text()
            matches=[p for p in candidates if str(p.relative_to(lo/'workdir').with_suffix('')) in mk]
            if len(matches)==1: candidates=matches; basis='build_definition'
            elif len(matches)==2:
                markers={'cffread.o':{'cffGetFontInfo','cfrNew'}, 'pstoken.o':{'psConvInteger','pstConvInteger'}}
                if obj in markers and markers[obj] <= observed_symbols[obj]: candidates=matches; basis='multiple_confirmed'
        if len(candidates)>1 and component=='boost' and archive.endswith('libboost_locale.a'):
            mk=(lo/'external/boost/StaticLibrary_boost_locale.mk').read_text().split('ifeq ($(OS),WNT)',1)[0]
            matches=[p for p in candidates if str(p.relative_to(lo/'workdir').with_suffix('')) in mk]
            if len(matches)==1: candidates=matches; basis='build_definition'
        if len(candidates)>1 and component=='harfbuzz':
            ninja=(lo/'workdir/UnpackedTarball/harfbuzz/builddir/build.ninja').read_text()
            matches=[p for p in candidates if re.search(r'^build [^\n]*' + re.escape(obj) + r': [^\n]*\.\./' + re.escape(str(p.relative_to(lo/'workdir/UnpackedTarball/harfbuzz'))) + r'(?:\s|$)', ninja, re.M)]
            if len(matches)==1: candidates=matches; basis='build_definition'
        if len(candidates)>1 and component=='freetype':
            log=(lo/'workdir/UnpackedTarball/freetype/build.log').read_text()
            lines=[line for line in log.splitlines() if re.search(r'-o\s+\S*/' + re.escape(obj) + r'(?:\s|$)', line)]
            matches=[p for p in candidates if any(str(p) in line or str(p.relative_to(lo/'workdir/UnpackedTarball/freetype')) in line for line in lines)]
            if len(matches)==1: candidates=matches; basis='build_log'
        if len(candidates)>1:
            matches=[p for p in candidates if (p.parent/obj).exists()]
            if len(matches)==1: candidates=matches; basis='object_directory'
        paths=' | '.join(str(p.relative_to(lo)) for p in candidates)
        writer.writerow([component,archive,obj,paths,basis])
print('objects',len(mapped),'components',len(wanted))
