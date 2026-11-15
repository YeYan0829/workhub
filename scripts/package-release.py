#!/usr/bin/env python3
"""Package a clean source revision and its labeled Docker image for a preview release."""
import argparse
import gzip
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--version', required=True)
p.add_argument('--output', default='target/release')
args = p.parse_args()
if not re.fullmatch(r'v\d+\.\d+\.\d+-preview\.\d+', args.version):
    p.error('Use a version such as v0.1.0-preview.1')
root = Path(__file__).resolve().parents[1]
out = Path(args.output).resolve()
out.mkdir(parents=True, exist_ok=True)
subprocess.run(['git', 'diff', '--exit-code', 'HEAD'], cwd=root, check=True)
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
tag = 'workhub:' + args.version
image = json.loads(subprocess.check_output(['docker', 'image', 'inspect', tag], text=True))[0]
labels = image['Config']['Labels']
assert labels['org.opencontainers.image.version'] == args.version
assert labels['org.opencontainers.image.revision'] == commit
assert labels['org.opencontainers.image.licenses'] == 'MIT'
assert image['Config']['User'] == '10001:10001'
platform = image['Os'] + '/' + image['Architecture']
assert platform == 'linux/amd64', platform
license_text = subprocess.check_output(['docker', 'run', '--rm', '--entrypoint', 'cat', tag, '/opt/workhub/LICENSE'])
assert license_text == (root / 'LICENSE').read_bytes()
source = out / ('workhub-' + args.version + '-source.tar.gz')
with source.open('wb') as f:
    subprocess.run(['git', 'archive', '--format=tar.gz', '--prefix=workhub-' + args.version + '/', commit],
                   cwd=root, stdout=f, check=True)
archive = out / ('workhub-' + args.version + '-linux-amd64.docker.tar.gz')
process = subprocess.Popen(['docker', 'image', 'save', tag], stdout=subprocess.PIPE)
with archive.open('wb') as f:
    with gzip.GzipFile(filename='', fileobj=f, mode='wb', compresslevel=6, mtime=0) as z:
        shutil.copyfileobj(process.stdout, z, 1024 * 1024)
assert process.wait() == 0


def digest(file):
    h = hashlib.sha256()
    with file.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


files = [source, archive]
manifest = {
    'version': args.version, 'sourceRepository': 'https://github.com/YeYan0829/workhub',
    'sourceCommit': commit, 'image': tag, 'imageId': image['Id'], 'platform': platform,
    'license': 'MIT', 'user': image['Config']['User'], 'delivery': 'docker-save archive',
    'labels': {k: v for k, v in labels.items() if k.startswith('org.opencontainers.image.')},
    'artifacts': [{'file': f.name, 'bytes': f.stat().st_size, 'sha256': digest(f)} for f in files],
}
metadata = out / 'release-manifest.json'
metadata.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
files.append(metadata)
(out / 'SHA256SUMS').write_text(''.join(digest(f) + '  ' + f.name + '\n' for f in files))
(out / 'release-notes.md').write_text(
    '# WorkHub ' + args.version + '\n\n'
    '面向多团队的项目协作与知识检索平台，支持任务依赖图、实时协作、项目资料与带原文引用的助手。\n\n'
    '下载本页源码包、Linux amd64 应用镜像、release-manifest.json 和 SHA256SUMS。'
    '按[镜像安装指南](https://github.com/YeYan0829/workhub/blob/' + args.version + '/docs/RELEASE.md)校验并启动。'
    '默认应用地址 http://127.0.0.1:18080，测试邮箱 http://127.0.0.1:18025。\n\n'
    '完成注册后，按[首次体验指南](https://github.com/YeYan0829/workhub/blob/' + args.version + '/docs/GETTING_STARTED.md)'
    '建立报表任务与依赖，体验阻塞提示、文档讨论和实时更新。模型与 GitHub 按需接入。\n\n'
    '版本对应源码提交 `' + commit + '`，应用镜像 `' + tag + '`，许可 MIT。'
    '附件 installation-verification.json 记录独立运行器从下载开始的安装、业务与重建验收。'
    '工程实测与后续验证见[实验报告](https://github.com/YeYan0829/workhub/blob/' + args.version + '/docs/VALIDATION.md)。\n')
print(json.dumps({'version': args.version, 'sourceCommit': commit, 'imageId': image['Id'],
                  'archiveBytes': archive.stat().st_size}))
