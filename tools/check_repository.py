#!/usr/bin/env python3
"""Check publishable source hygiene and local Markdown links without exposing secrets."""
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED_PARTS = {'.tooling', '.roots', 'target', '__pycache__', 'artifacts'}
GENERATED = {'.class', '.jar', '.war', '.zip', '.jfr', '.hprof', '.heapdump', '.p12', '.pfx', '.key'}
SECRET = re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\b(?:AKIA|ASIA)[A-Z0-9]{16}\b|\bgh[pousr]_[A-Za-z0-9]{36,}\b|\bgithub_pat_[A-Za-z0-9_]{70,}\b')

def source_paths():
    output = subprocess.check_output(['git', 'ls-files', '-c', '-o', '--exclude-standard', '-z'], cwd=ROOT)
    return sorted({Path(name.decode('utf-8')) for name in output.split(b'\0') if name})

def slug(heading):
    heading = re.sub(r'<[^>]+>', '', heading).strip().lower()
    return re.sub(r'[^\w\- ]', '', heading).replace(' ', '-')

def anchors(text):
    found = set()
    counts = {}
    for heading in re.findall(r'^#{1,6}\s+(.+?)\s*#*$', text, re.M):
        base = slug(heading)
        count = counts.get(base, 0)
        found.add(base + ('-' + str(count) if count else ''))
        counts[base] = count + 1
    found.update(re.findall(r'\bid=["\']([^"\']+)', text))
    return found

def main():
    errors = []
    paths = [p for p in source_paths() if (ROOT / p).is_file()]
    sources = {p.as_posix() for p in paths}
    for relative in paths:
        path = ROOT / relative
        name = relative.as_posix()
        if EXCLUDED_PARTS.intersection(relative.parts) or path.suffix.lower() in GENERATED:
            errors.append(f'{name}: generated/local/private material belongs outside published source')
            continue
        if relative.name.startswith('.env') and relative.name != '.env.example':
            errors.append(f'{name}: environment file must not be published')
        if path.is_symlink() or not path.resolve().is_relative_to(ROOT.resolve()):
            errors.append(f'{name}: source must stay inside the repository')
            continue
        try:
            text = path.read_text(encoding='utf-8')
        except (UnicodeDecodeError, OSError):
            continue
        if SECRET.search(text):
            errors.append(f'{name}: possible credential/private key (value redacted)')
        if path.suffix == '.java' and re.search(r'\b(?:package|import)\s+dev\.roots\b', text):
            errors.append(f'{name}: stale Java namespace')
        if path.suffix != '.md':
            continue
        prose = re.sub(r'^```.*?^```[^\n]*', '', text, flags=re.M | re.S)
        for target in re.findall(r'\]\(([^)\s]+)(?:\s+"[^"]*")?\)', prose):
            target = target.strip('<>')
            url = urlsplit(target)
            if url.scheme or target.startswith('//'):
                continue
            local = (path.parent / unquote(url.path)).resolve() if url.path else path
            if not local.is_relative_to(ROOT.resolve()):
                errors.append(f'{name}: link leaves repository: {target}')
                continue
            link_path = local.relative_to(ROOT.resolve())
            if any(part in {'target', '.tooling'} for part in link_path.parts):
                continue  # Documented build outputs are intentionally absent from source.
            if not local.exists() or (local.is_file() and link_path.as_posix() not in sources):
                errors.append(f'{name}: missing source link: {target}')
            elif url.fragment and local.suffix == '.md':
                if unquote(url.fragment) not in anchors(local.read_text(encoding='utf-8')):
                    errors.append(f'{name}: missing heading: {target}')
    if errors:
        print('\n'.join(errors))
        return 1
    print(f'Repository hygiene passed: {len(paths)} source files; local Markdown links and namespace checked.')
    return 0

if __name__ == '__main__':
    sys.exit(main())
