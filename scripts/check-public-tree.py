#!/usr/bin/env python3
"""Validate the public working tree, or the exact Git index with --staged."""
import argparse
import json
import posixpath
import re
import subprocess
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlsplit

ROOT_FILES = {
    '.env.example', '.gitattributes', '.gitignore', 'README.md', 'LICENSE', 'pom.xml',
    'Dockerfile', '.dockerignore', 'docker/Healthcheck.java',
    'mvnw', 'mvnw.cmd', 'package.json', 'package-lock.json',
    'compose.yaml', 'compose.test.yaml', 'compose.conversation-test.yaml',
    'compose.governance-test.yaml', 'compose.team-model-test.yaml',
    '.mvn/jvm.config', '.mvn/maven.config', '.mvn/wrapper/maven-wrapper.properties',
    '.github/workflows/public-tree.yml',
}
DOC_FILES = {
    'docs/ARCHITECTURE.md', 'docs/TESTING.md', 'docs/VALIDATION.md', 'docs/GETTING_STARTED.md', 'docs/RELEASE.md',
    'docs/assets/project-overview.png', 'docs/assets/project-task.png', 'docs/assets/project-collaboration.png', 'docs/assets/model-settings.png',
}
SCRIPT_FILES = {
    'test-project-overview-ui.sh', 'verify-project-overview-ui.cjs',
    'test-project-overview-realtime.sh', 'verify-project-overview-realtime.cjs', 'verify-overview-instance-switch.cjs', 'verify-overview-under-load.cjs', 'verify-overview-entry-under-load.cjs', 'benchmark-project-overview.cjs', 'load-project-overview.cjs',
    'admin.sh', 'bootstrap-jdk.py', 'init-env.py', 'java-env.sh', 'jdk-lock.json',
    'test-team-model.sh', 'init-team-model-test.sh', 'test-model-settings-ui.sh', 'verify-model-settings-ui.cjs', 'model-admission.py', 'mysql-init.sh', 'run-app.sh', 'run.sh', 'test-core.sh',
    'init-test-env.sh', 'init-phase1-test-db.sh', 'init-phase2-test-env.sh',
    'init-phase3-test-env.sh', 'init-phase3x-test-env.sh',
    'init-phase4-test-env.sh', 'init-phase4b-test-env.sh', 'init-phase5a-test-env.sh',
    'verify-automatic-triage-ui.cjs', 'verify-phase5b-ui.cjs', 'verify-w3-progress.cjs',
    'check-public-tree.py', 'test-public-tree.py', 'verify-docker-ui.cjs', 'verify-frontend-ui.cjs', 'verify-first-run.cjs',
}
REQUIRED = ROOT_FILES | DOC_FILES | {'scripts/' + name for name in SCRIPT_FILES}
FORBIDDEN_PARTS = {
    '.git', '.tools', '.claude', '.codex', '.agents', 'target', 'node_modules',
    'evidence', 'history', 'interview-learning', 'archives', 'logs', '__pycache__',
}
REMOVED_EXPERIMENTS = {
    'GovernanceExperimentWorker.java', 'PermitTimingProbe.java',
    'NonzeroBudgetApplication.java',
}
PROCESS_REFERENCES = (
    'SOURCE.md', 'BASELINE_VALIDATION.md', 'WORKHUB_PROJECT_AUDIT',
    'WORKHUB_CAREER_MASTER', 'interview-learning/', '../evidence/',
    '/home/a1872/', '本轮', '求职', '简历', '面试',
)


def allowed(name):
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts:
        return False
    if FORBIDDEN_PARTS.intersection(path.parts) or path.name in REMOVED_EXPERIMENTS:
        return False
    if name in ROOT_FILES | DOC_FILES:
        return True
    if name.startswith('scripts/'):
        return name.removeprefix('scripts/') in SCRIPT_FILES
    if name.startswith(('src/main/java/dev/workhub/', 'src/test/java/dev/workhub/')):
        return path.suffix == '.java'
    if name.startswith('src/main/resources/'):
        return path.suffix in {'.yml', '.yaml', '.sql', '.lua', '.html', '.js', '.css', '.csv'}
    if name.startswith('src/test/resources/'):
        return path.suffix in {'.sql', '.json', '.md'}
    return False


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args])


def snapshot(root, staged=False):
    """Read index blobs in staged mode; never inspect an unstaged replacement."""
    entries = git(root, 'ls-files', '--stage', '-z').split(b'\0')
    files, problems = {}, []
    for entry in entries:
        if not entry:
            continue
        meta, raw_name = entry.split(b'\t', 1)
        mode, oid, stage = meta.decode().split()
        name = raw_name.decode()
        if stage != '0':
            problems.append(f'Unresolved merge: {name}')
            continue
        path = root / name
        if not staged and not path.exists() and not path.is_symlink():
            continue
        if mode not in {'100644', '100755'} or (not staged and path.is_symlink()):
            problems.append(f'Only regular files are allowed: {name}')
            continue
        files[name] = git(root, 'cat-file', 'blob', oid) if staged else path.read_bytes()
    if not staged:
        for raw_name in git(root, 'ls-files', '--others', '--exclude-standard', '-z').split(b'\0'):
            if not raw_name:
                continue
            name = raw_name.decode()
            path = root / name
            if path.is_symlink() or not path.is_file():
                problems.append(f'Only regular files are allowed: {name}')
            else:
                files[name] = path.read_bytes()
    return files, problems


def target_exists(files, document, target):
    target = unquote(urlsplit(target.strip('<>')).path)
    name = posixpath.normpath(posixpath.join(posixpath.dirname(document), target))
    return name in files or any(p.startswith(name.rstrip('/') + '/') for p in files)


def validate(files, require_complete=True):
    errors = [f'Outside public scope: {name}' for name in sorted(files) if not allowed(name)]
    if require_complete:
        errors.extend(f'Missing required file: {name}' for name in sorted(REQUIRED - files.keys()))
    for name, data in sorted(files.items()):
        if name == 'README.md' or name.startswith('docs/') and name.endswith('.md'):
            body = data.decode('utf-8')
            for marker in PROCESS_REFERENCES:
                if marker in body:
                    errors.append(f'Private or process reference in {name}: {marker}')
            for target in re.findall(r'\]\(([^\s)]+)', body):
                if target.startswith('#') or urlsplit(target).scheme:
                    continue
                if not target_exists(files, name, target):
                    errors.append(f'Missing local link in {name}: {target}')
        if name.startswith('compose') and name.endswith(('.yaml', '.yml')):
            body = data.decode('utf-8')
            for target in re.findall(r'^\s*-\s+[\"\x27]?(\./[^:\s]+):', body, re.MULTILINE):
                if not target_exists(files, name, target):
                    errors.append(f'Missing Compose bind source in {name}: {target}')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--staged', action='store_true', help='validate exact staged paths and blobs')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    files, errors = snapshot(root, args.staged)
    errors.extend(validate(files))
    if errors:
        raise SystemExit('\n'.join(errors))
    print(json.dumps({'status': 'OK', 'files': len(files),
                      'snapshot': 'index' if args.staged else 'working-tree'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
