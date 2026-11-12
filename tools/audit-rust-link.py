import argparse
import csv
import json
import re
from collections import Counter
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('link_map', type=Path)
parser.add_argument('cargo_metadata', type=Path)
parser.add_argument('target_deps', type=Path)
args = parser.parse_args()
workspace = Path(__file__).resolve().parents[1]
metadata = json.loads(args.cargo_metadata.read_text(encoding='utf-8'))
packages = metadata['packages']
archives = Counter()
sysroot_archives = set()
pattern = re.compile(r'/lib([^/]+)-([0-9a-f]{16})\.rlib\(')
with args.link_map.open(encoding='utf-8', errors='replace') as source:
    for line in source:
        match = pattern.search(line)
        if match:
            archives[(match.group(1), match.group(2))] += 1
            if '/lib/rustlib/wasm32-unknown-unknown/lib/' in line:
                sysroot_archives.add((match.group(1), match.group(2)))

out = workspace / 'docs/license/rhwp-linked-archives.tsv'
with out.open('w', encoding='utf-8', newline='') as file:
    writer = csv.writer(file, delimiter='\t', lineterminator='\n')
    writer.writerow(['crate','artifact_hash','package','version','license','source_kind','map_entries','match'])
    for (crate, artifact_hash), count in sorted(archives.items()):
        depfile = args.target_deps / f'{crate}-{artifact_hash}.d'
        dependencies = depfile.read_text(encoding='utf-8', errors='replace') if depfile.exists() else ''
        matches = []
        for package in packages:
            root = Path(package['manifest_path']).parent
            if str(root) + '/' in dependencies:
                matches.append(package)
            elif root.is_relative_to(metadata['workspace_root']):
                relative = root.relative_to(metadata['workspace_root']).as_posix()
                if relative != '.' and re.search(r'(?:\s|^)' + re.escape(relative) + r'/', dependencies):
                    matches.append(package)
        if (crate, artifact_hash) in sysroot_archives:
            versions = {'dlmalloc':'0.2.11','hashbrown':'0.16.1','compiler_builtins':'0.1.160'}
            licenses = {'dlmalloc':'MIT OR Apache-2.0','hashbrown':'MIT OR Apache-2.0','compiler_builtins':'MIT AND Apache-2.0 WITH LLVM-exception'}
            writer.writerow([crate,artifact_hash,crate,versions.get(crate,'Rust 1.93.1'),licenses.get(crate,'MIT OR Apache-2.0'),'rust-1.93.1-sysroot',count,'toolchain-archive'])
        elif len(matches) == 1:
            package = matches[0]
            writer.writerow([crate,artifact_hash,package['name'],package['version'],package['license'] or '',package['source'] or 'workspace-or-path',count,'metadata-and-depfile'])
        else:
            writer.writerow([crate,artifact_hash,'','','','rust-sysroot-or-unmatched',count,'unmatched' if not matches else 'ambiguous'])
print('linked archive identities:', len(archives))
