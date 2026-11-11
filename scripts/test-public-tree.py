#!/usr/bin/env python3
"""Regression checks for public scope and staged-file bypasses; no external services."""
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('public_tree', Path(__file__).with_name('check-public-tree.py'))
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class ScopeTests(unittest.TestCase):
    def test_regular_code_and_test_fixtures_are_public(self):
        files = {
            'src/main/java/dev/workhub/agent/AgentAudit.java': b'package dev.workhub.agent;',
            'src/test/java/dev/workhub/conversation/CrashWorker.java': b'class CrashWorker {}',
            'src/test/resources/phase5a/long-material.md': b'Fixed model input',
        }
        self.assertEqual([], policy.validate(files, require_complete=False))

    def test_private_materials_are_rejected_even_under_source(self):
        for name in ['SOURCE.md', 'docs/BASELINE_VALIDATION.md', '.env',
                     'evidence/results.json', 'docs/interview-learning/notes.md',
                     'src/test/resources/evidence/results.json', 'scripts/work-log.md',
                     'src/main/resources/run.log', 'target/report.xml',
                     'src/test/java/dev/workhub/governance/GovernanceExperimentWorker.java']:
            with self.subTest(name=name):
                self.assertTrue(policy.validate({name: b''}, require_complete=False))

    def test_missing_runtime_dependencies_are_rejected(self):
        self.assertIn('Missing required file: scripts/mysql-init.sh', policy.validate({}))

    def test_links_must_resolve_inside_the_same_snapshot(self):
        files = {'README.md': b'![Product](docs/assets/project-overview.png)\n[Tests](docs/TESTING.md#core)',
                 'docs/assets/project-overview.png': b'png'}
        errors = policy.validate(files, require_complete=False)
        self.assertEqual(['Missing local link in README.md: docs/TESTING.md#core'], errors)

    def test_external_links_and_fragment_links_need_no_network(self):
        files = {'README.md': b'[Web](https://example.com) [Email](mailto:team@example.test) [Here](#demo)'}
        self.assertEqual([], policy.validate(files, require_complete=False))

    def test_private_document_references_are_rejected_without_a_link(self):
        errors = policy.validate({'docs/ARCHITECTURE.md': b'See WORKHUB_PROJECT_AUDIT'}, require_complete=False)
        self.assertTrue(any('process reference' in e for e in errors))

    def test_compose_bind_sources_must_be_in_the_public_tree(self):
        body = b'services:\n  mysql:\n    volumes:\n      - ./scripts/mysql-init.sh:/init.sh:ro\n      - mysql-data:/var/lib/mysql\n'
        files = {'compose.yaml': body}
        self.assertTrue(policy.validate(files, require_complete=False))
        files['scripts/mysql-init.sh'] = b'#!/bin/sh\n'
        self.assertEqual([], policy.validate(files, require_complete=False))


class IndexTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], stderr=subprocess.STDOUT)

    def test_unstaged_replacement_cannot_hide_staged_private_content(self):
        path = self.root / 'README.md'
        path.write_text('See WORKHUB_PROJECT_AUDIT')
        self.git('add', 'README.md')
        path.write_text('# Product\n')
        staged, errors = policy.snapshot(self.root, staged=True)
        self.assertEqual([], errors)
        self.assertTrue(policy.validate(staged, require_complete=False))
        working, errors = policy.snapshot(self.root)
        self.assertEqual([], errors)
        self.assertEqual([], policy.validate(working, require_complete=False))

    def test_ignored_but_tracked_private_file_is_still_rejected(self):
        (self.root / '.gitignore').write_text('.env\n')
        (self.root / '.env').write_text('LOCAL=fixture\n')
        self.git('add', '-f', '.env')
        staged, errors = policy.snapshot(self.root, staged=True)
        self.assertEqual([], errors)
        self.assertEqual(['Outside public scope: .env'], policy.validate(staged, require_complete=False))

    def test_untracked_process_files_are_checked_and_ignored_outputs_are_not(self):
        (self.root / '.gitignore').write_text('target/\n')
        (self.root / 'target').mkdir()
        (self.root / 'target/result.json').write_text('{}')
        (self.root / 'work-log.md').write_text('notes')
        files, errors = policy.snapshot(self.root)
        self.assertEqual([], errors)
        self.assertNotIn('target/result.json', files)
        self.assertIn('Outside public scope: work-log.md', policy.validate(files, require_complete=False))

    def test_symlink_is_not_a_way_to_publish_files_outside_the_tree(self):
        (self.root / 'README.md').symlink_to('/etc/hosts')
        self.git('add', 'README.md')
        for staged in (True, False):
            with self.subTest(staged=staged):
                _, errors = policy.snapshot(self.root, staged=staged)
                self.assertEqual(['Only regular files are allowed: README.md'], errors)


if __name__ == '__main__':
    unittest.main()
