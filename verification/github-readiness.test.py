"""首次安装入口的保护边界验证；不访问任何数据库。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import subprocess

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("initialize_database", ROOT / "scripts/init-database.py")
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class Cursor:
    def __init__(self, rows, fail_create=False):
        self.rows = iter(rows)
        self.commands = []
        self.fail_create = fail_create

    def __enter__(self): return self
    def __exit__(self, *args): return False
    def execute(self, query, params=None):
        self.commands.append((query, params))
        if self.fail_create and query.startswith("CREATE DATABASE"):
            raise RuntimeError("模拟并发首次安装抢先创建")

    def fetchone(self): return next(self.rows)


class Connection:
    def __init__(self, cursor): self.target = cursor
    def cursor(self): return self.target
    def commit(self): pass


class GithubReadiness(unittest.TestCase):
    def test_git_excludes_private_generated_and_database_dump_files(self):
        probes = [".local/backend.properties", ".local/wechat-pay.properties", ".runtime/trace.json",
                  ".tools/redis/redis-server.exe", ".env", "admin-web/.env.production",
                  "backend/uploads/avatars/example.png", "private.pem", "backup.sql",
                  "backend/data/export.sql", "wechat-miniprogram/miniprogram/utils/network-config.js",
                  "wechat-miniprogram/miniprogram/miniprogram_npm/@vant/weapp/button/index.js",
                  "admin-web/.env.example", "config-templates/wechat.properties.example",
                  "database/schema.sql", "database/demo-seed.sql",
                  "backend/src/main/resources/native-payment-schema.sql"]
        with tempfile.TemporaryDirectory() as metadata:
            subprocess.run(["git", "init", "--bare", "--quiet", metadata], check=True)
            result = subprocess.run(["git", "--git-dir=" + metadata, "--work-tree=" + str(ROOT), "-c",
                                     "core.excludesfile=NUL", "check-ignore", "--no-index", "--stdin", "-z"],
                                    input=("\0".join(probes) + "\0").encode("utf-8"),
                                    capture_output=True, cwd=ROOT)
            self.assertIn(result.returncode, [0, 1])
            ignored = set(result.stdout.decode("utf-8").split("\0"))
        self.assertTrue(all(path in ignored for path in probes[:12]))
        self.assertTrue(all(path not in ignored for path in probes[12:]))

    def test_existing_database_is_rejected_before_any_write(self):
        cursor = Cursor([("existing",)])
        with self.assertRaises(ValueError):
            setup.install(Connection(cursor), [], [], "synthetic", "synthetic-hash")
        self.assertTrue(all(query.startswith("SELECT ") for query, _ in cursor.commands))

    def test_existing_account_is_rejected_before_any_write(self):
        cursor = Cursor([None, ("existing-account",)])
        with self.assertRaises(ValueError):
            setup.install(Connection(cursor), [], [], "synthetic", "synthetic-hash")
        self.assertTrue(all(query.startswith("SELECT ") for query, _ in cursor.commands))

    def test_concurrent_creation_failure_never_drops_or_alters_objects(self):
        cursor = Cursor([None, None], fail_create=True)
        with self.assertRaises(RuntimeError):
            setup.install(Connection(cursor), [], [], "synthetic", "synthetic-hash")
        self.assertFalse(any(query.startswith(("DROP ", "ALTER ", "GRANT ", "USE "))
                             for query, _ in cursor.commands))

    def test_passwords_are_bound_as_parameters(self):
        cursor = Cursor([None, None])
        setup.install(Connection(cursor), [], [], "synthetic-app-password", "synthetic-admin-hash")
        self.assertTrue(all("synthetic-" not in query for query, _ in cursor.commands))
        self.assertTrue(any(params and "synthetic-app-password" in params for _, params in cursor.commands))

    def test_schema_is_structure_only_and_has_all_current_tables(self):
        schema, _ = setup.load_install_sql()
        self.assertEqual(len(schema), 17)
        self.assertTrue(all(statement.startswith("CREATE TABLE ") for statement in schema))
        self.assertNotIn("AUTO_INCREMENT=", "\n".join(schema))

    def test_seed_contains_no_patients_accounts_transactions_or_photos(self):
        _, seed = setup.load_install_sql()
        self.assertEqual(len(seed), 3)
        self.assertTrue(all(statement.startswith(("INSERT INTO sys_menu ", "INSERT INTO department ",
                                                  "INSERT INTO medicine ")) for statement in seed))
        self.assertNotIn("/api/avatar/", "\n".join(seed))

    def test_sql_split_keeps_quoted_semicolons_and_ignores_comments(self):
        statements = setup.sql_statements("-- ignored;\nSELECT 'a;b'; /* ignored; */ SELECT `x;y`; # ignored;\n")
        self.assertEqual(statements, ["SELECT 'a;b'", "SELECT `x;y`"])
        with self.assertRaises(ValueError): setup.sql_statements("SELECT 'unterminated;")

    def test_placeholder_password_is_not_accepted_as_database_credential(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / ".local").mkdir()
            config = root / ".local/backend.properties"
            for value in ["", "${LOCAL_MYSQL_PASSWORD:}", "__MYSQL_PASSWORD__"]:
                config.write_text("spring.datasource.password=" + value, encoding="utf-8")
                with self.assertRaises(ValueError): setup.read_app_password(root)


if __name__ == "__main__":
    unittest.main(verbosity=2)
