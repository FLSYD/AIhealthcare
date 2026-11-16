"""创建只含表结构及合成测试数据的专属 MySQL 库，不复制业务记录。"""
from pathlib import Path
import argparse
import json
import os
import re
import secrets
import sys
import uuid
from datetime import datetime

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / ".runtime" / "python"))
import pymysql


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--admin-user", default="root")
    args = parser.parse_args()
    password = os.environ.get("HEALTHCARE_VERIFY_ADMIN_PASSWORD")
    if not password:
        raise SystemExit("请提供 HEALTHCARE_VERIFY_ADMIN_PASSWORD，脚本不会保存管理密码。")
    config = Path(args.config).resolve()
    evidence = Path(args.evidence).resolve()
    if not config.is_relative_to(ROOT / ".local") or not evidence.is_relative_to(ROOT / ".runtime"):
        raise SystemExit("测试凭据和证据路径必须位于当前项目的专属目录。")
    if config.exists():
        raise SystemExit("测试配置已存在，拒绝覆盖。")
    suffix = uuid.uuid4().hex[:8]
    schema = "aihealthcare_verify_" + datetime.now().strftime("%Y%m%d") + "_" + suffix
    user = "ahv_" + suffix
    marker = uuid.uuid4().hex
    app_password = secrets.token_urlsafe(30)
    assert re.fullmatch(r"aihealthcare_verify_[0-9]{8}_[a-f0-9]{8}", schema)
    connection = pymysql.connect(host="127.0.0.1", port=3306, user=args.admin_user,
                                 password=password, autocommit=True, connect_timeout=5)
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s", (schema,))
            if cursor.fetchone():
                raise RuntimeError("目标测试库已存在，拒绝复用。")
            cursor.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
            cursor.execute("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='aihealthcare_local' AND TABLE_TYPE='BASE TABLE'")
            tables = [row[0] for row in cursor.fetchall()]
            for table in tables:
                if not re.fullmatch(r"[a-z_]+", table):
                    raise RuntimeError("表名未通过白名单。")
                cursor.execute("CREATE TABLE " + schema + "." + table + " LIKE aihealthcare_local." + table)
            cursor.execute("CREATE TABLE " + schema + "._verification_guard (id INT PRIMARY KEY, run_marker VARCHAR(64) NOT NULL)")
            cursor.execute("INSERT INTO " + schema + "._verification_guard VALUES (1,%s)", (marker,))
            cursor.execute("CREATE TABLE " + schema + "._verification_fault (name VARCHAR(40) PRIMARY KEY)")
            cursor.execute("CREATE TRIGGER " + schema + ".verify_fail_balance_log BEFORE INSERT ON " + schema + ".balance_log FOR EACH ROW BEGIN IF EXISTS (SELECT 1 FROM " + schema + "._verification_fault WHERE name='balance_log') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='verification balance-log failure'; END IF; END")
            cursor.execute("CREATE TRIGGER " + schema + ".verify_fail_dispense BEFORE UPDATE ON " + schema + ".prescription FOR EACH ROW BEGIN IF NEW.status='dispensed' AND EXISTS (SELECT 1 FROM " + schema + "._verification_fault WHERE name='dispense') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='verification dispense failure'; END IF; END")
            cursor.execute("CREATE TRIGGER " + schema + ".verify_fail_notification BEFORE INSERT ON " + schema + ".user_notification FOR EACH ROW BEGIN IF EXISTS (SELECT 1 FROM " + schema + "._verification_fault WHERE name='notification') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='verification notification failure'; END IF; END")
            cursor.execute("CREATE USER %s@'127.0.0.1' IDENTIFIED BY %s", (user, app_password))
            cursor.execute("GRANT ALL PRIVILEGES ON " + schema + ".* TO %s@'127.0.0.1'", (user,))
    finally:
        connection.close()
    app = pymysql.connect(host="127.0.0.1", user=user, password=app_password,
                          database=schema, connect_timeout=5)
    try:
        with app.cursor() as cursor:
            cursor.execute("SELECT DATABASE(),CURRENT_USER(),@@version,@@transaction_isolation")
            identity = cursor.fetchone()
            try:
                cursor.execute("SELECT COUNT(*) FROM aihealthcare_local.sys_user")
                raise RuntimeError("专属测试账号意外可以访问业务库。")
            except pymysql.err.OperationalError as error:
                if error.args[0] != 1142:
                    raise
            cursor.execute("SHOW GRANTS")
            grants = [row[0] for row in cursor.fetchall()]
    finally:
        app.close()
    config.parent.mkdir(parents=True, exist_ok=True)
    evidence.mkdir(parents=True, exist_ok=True)
    config.write_text(
        "jdbc.url=jdbc:mysql://127.0.0.1:3306/" + schema + "?characterEncoding=UTF-8&serverTimezone=Asia/Hong_Kong\n"
        "jdbc.username=" + user + "\njdbc.password=" + app_password + "\nrun.marker=" + marker + "\n",
        encoding="utf-8")
    report = {"schema": schema, "username": user, "identity": identity, "grants": grants,
              "schema_tables": tables, "business_rows_copied": 0, "business_access_denied": True,
              "marker": marker}
    (evidence / "database-isolation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({key: report[key] for key in ["schema", "username", "business_rows_copied", "business_access_denied"]}))


if __name__ == "__main__":
    main()
