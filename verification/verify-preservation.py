"""核对业务库数据内容指纹；只在证据中保存散列和行数，不保存业务明细。"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / ".runtime" / "python"))
import pymysql


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    if not output.is_relative_to(ROOT / ".runtime"):
        raise SystemExit("证据必须写入项目运行目录。")
    settings = dict(re.findall(r"^([^#\s][^=]*)=(.*)$",
                              (ROOT / ".local/backend.properties").read_text(encoding="utf-8"), re.M))
    connection = pymysql.connect(host="127.0.0.1", port=3306, user="aihealthcare_app",
                                 password=settings["spring.datasource.password"].strip(),
                                 database="aihealthcare_local", connect_timeout=5, read_timeout=30)
    result = {"schema": "aihealthcare_local", "tables": {}}
    try:
        with connection.cursor() as cursor:
            cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
            cursor.execute("SHOW TABLES")
            tables = [row[0] for row in cursor.fetchall()]
            for table in tables:
                if not re.fullmatch(r"[a-z_]+", table):
                    raise RuntimeError("非预期表名。")
                cursor.execute("SELECT * FROM " + table + " ORDER BY id")
                digest = hashlib.sha256()
                rows = cursor.fetchall()
                for row in rows:
                    encoded = json.dumps(row, default=str, ensure_ascii=False, separators=(",", ":")).encode()
                    digest.update(len(encoded).to_bytes(8, "big"))
                    digest.update(encoded)
                result["tables"][table] = {"rows": len(rows), "sha256": digest.hexdigest()}
            connection.rollback()
    finally:
        connection.close()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print("Business database read-only fingerprints recorded; no row contents written.")


if __name__ == "__main__":
    main()
