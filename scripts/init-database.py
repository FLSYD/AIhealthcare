"""首次安装：只创建全新本地数据库和账号，已有目标一律拒绝覆盖。"""
from __future__ import annotations

import argparse
import getpass
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATABASE = "aihealthcare_local"
APP_USER = "aihealthcare_app"
TABLES = {
    "appointment", "appointment_review", "balance_log", "consultation_record", "department", "doctor",
    "medicine", "prescription", "prescription_item", "schedule", "sys_login_log", "sys_menu",
    "sys_operation_log", "sys_role", "sys_user", "user_notification", "wechat_native_payment",
}


def sql_statements(source: str) -> list[str]:
    """按语句拆分，保留字符串内部的分号，并忽略 SQL 注释。"""
    result, buffer = [], []
    quote = None
    index = 0
    while index < len(source):
        char = source[index]
        if quote:
            buffer.append(char)
            if char == "\\" and index + 1 < len(source):
                index += 1
                buffer.append(source[index])
            elif char == quote:
                if index + 1 < len(source) and source[index + 1] == quote:
                    index += 1
                    buffer.append(source[index])
                else:
                    quote = None
        elif char in "'\"`":
            quote = char
            buffer.append(char)
        elif char == "#" or (source[index:index + 2] == "--" and
                             (index + 2 == len(source) or source[index + 2].isspace())):
            end = source.find("\n", index)
            index = len(source) if end < 0 else end
            buffer.append("\n")
            continue
        elif source[index:index + 2] == "/*":
            end = source.find("*/", index + 2)
            if end < 0:
                raise ValueError("SQL注释未闭合")
            index = end + 2
            buffer.append(" ")
            continue
        elif char == ";":
            if "".join(buffer).strip():
                result.append("".join(buffer).strip())
            buffer = []
        else:
            buffer.append(char)
        index += 1
    if quote:
        raise ValueError("SQL字符串未闭合")
    if "".join(buffer).strip():
        result.append("".join(buffer).strip())
    return result


def read_app_password(root: Path = ROOT) -> str:
    settings = {}
    for line in (root / ".local/backend.properties").read_text(encoding="utf-8-sig").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            settings[key.strip()] = value.strip()
    password = settings.get("spring.datasource.password", "")
    if not password or password.startswith(("${", "__")) or any(char in password for char in "\r\n\0"):
        raise ValueError("请先运行 setup-local.ps1 生成本地数据库密码")
    return password


def load_install_sql(root: Path = ROOT) -> tuple[list[str], list[str]]:
    schema = sql_statements((root / "database/schema.sql").read_text(encoding="utf-8-sig"))
    names = []
    for statement in schema:
        match = re.match(r"CREATE\s+TABLE\s+`([a-z_]+)`\s*\(", statement, re.I)
        if not match:
            raise ValueError("结构文件只能包含 CREATE TABLE")
        names.append(match.group(1))
    if len(names) != len(TABLES) or set(names) != TABLES:
        raise ValueError("初始化结构与项目表清单不符")
    seed = sql_statements((root / "database/demo-seed.sql").read_text(encoding="utf-8-sig"))
    for statement in seed:
        match = re.match(r"INSERT\s+INTO\s+(\w+)\s*\(", statement, re.I)
        if not match or match.group(1) not in {"sys_menu", "department", "medicine"}:
            raise ValueError("演示数据只能包含菜单、科室和药品")
    return schema, seed


def require_fresh_target(cursor) -> None:
    cursor.execute("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s", (DATABASE,))
    if cursor.fetchone():
        raise ValueError("目标数据库已存在，拒绝初始化；当前本地项目无需重复执行")
    cursor.execute("SELECT User FROM mysql.user WHERE User=%s", (APP_USER,))
    if cursor.fetchone():
        raise ValueError("项目数据库账号已存在，拒绝创建或修改该账号")


def install(connection, schema: list[str], seed: list[str], app_password: str, admin_hash: str) -> None:
    with connection.cursor() as cursor:
        # 在任何 DDL、账号或数据写操作前检查。并发 CREATE 失败也不会删除别人的库或账号。
        require_fresh_target(cursor)
        cursor.execute("CREATE DATABASE `aihealthcare_local` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
        cursor.execute("USE `aihealthcare_local`")
        cursor.execute("SET FOREIGN_KEY_CHECKS=0")
        try:
            for statement in schema:
                cursor.execute(statement)
        finally:
            cursor.execute("SET FOREIGN_KEY_CHECKS=1")
        for statement in seed:
            cursor.execute(statement)
        cursor.execute("INSERT INTO sys_user (username,password,nickname,role,status,deleted,balance) "
                       "VALUES (%s,%s,%s,'ADMIN',1,0,0.00)", ("admin", admin_hash, "演示管理员"))
        connection.commit()
        cursor.execute("CREATE USER %s@'127.0.0.1' IDENTIFIED BY %s", (APP_USER, app_password))
        cursor.execute("GRANT ALL PRIVILEGES ON `aihealthcare_local`.* TO %s@'127.0.0.1'", (APP_USER,))
        connection.commit()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--admin-user", default="root", help="仅用于首次建库的本机 MySQL 管理账号")
    args = parser.parse_args()
    try:
        import bcrypt
        import pymysql
        app_password = read_app_password()
        schema, seed = load_install_sql()
        mysql_password = getpass.getpass("本地 MySQL 管理账号密码（隐藏输入，不保存）：")
        initial_password = getpass.getpass("设置新后台 admin 登录密码（6-72字节，隐藏输入）：")
        if not 6 <= len(initial_password.encode("utf-8")) <= 72:
            raise ValueError("后台管理员密码须为6-72字节")
        if initial_password != getpass.getpass("再次输入新后台 admin 登录密码："):
            raise ValueError("两次管理员密码不一致")
        admin_hash = bcrypt.hashpw(initial_password.encode("utf-8"), bcrypt.gensalt()).decode("ascii")
        connection = pymysql.connect(host="127.0.0.1", port=3306, user=args.admin_user,
                                     password=mysql_password, charset="utf8mb4", autocommit=False,
                                     connect_timeout=5, read_timeout=30)
        try:
            install(connection, schema, seed, app_password, admin_hash)
        finally:
            connection.close()
    except ImportError:
        print("缺少首次安装依赖，请先执行 python -m pip install -r scripts/requirements-setup.txt")
        return 1
    except (ValueError, FileNotFoundError) as error:
        print(str(error))
        return 1
    except pymysql.MySQLError as error:
        # 仅输出数字错误码，不能回显 SQL 参数、管理密码或连接异常原文。
        code = error.args[0] if error.args and isinstance(error.args[0], int) else "unknown"
        print(f"初始化未完成（MySQL错误码 {code}）。请检查新建对象；脚本不会自动删除数据库或账号。")
        return 1
    except (Exception, KeyboardInterrupt):
        # 数据库异常可能含连接信息，避免把完整异常及参数写入终端或日志。
        print("初始化未完成。未覆盖已有库或账号；若已新建部分对象，请由管理员检查后处理，本脚本不会自动删除对象。")
        return 1
    print("初始化完成：后台账号 admin，密码为刚才输入的值。仅导入表结构和合成演示数据，真实微信支付默认关闭。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
