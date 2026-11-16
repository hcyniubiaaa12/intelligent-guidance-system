import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 没有 psql 客户端时执行 pgvector 迁移脚本的一次性 JDBC 运行器。
 *
 * <p>用法（Java 11+ 单文件源码启动）：
 * <pre>
 *   java -cp &lt;postgresql-jdbc.jar&gt; scripts/sql/PgExec.java scripts/sql/pgvector_memory_migration.sql
 * </pre>
 * 连接参数只从环境变量取，不写死、不进提交：
 * <pre>
 *   PGVECTOR_URL      jdbc:postgresql://host:5432/guide_vec
 *   PGVECTOR_USER     postgres
 *   PGVECTOR_PASSWORD ******
 * </pre>
 * 只做最朴素的 `;` 切分（忽略 `--` 注释行）；本目录的 pgvector 脚本没有函数体，
 * 不涉及 `$$ ... $$`，够用。
 */
public final class PgExec {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("用法: java -cp <postgresql.jar> scripts/sql/PgExec.java <sql-file>");
            System.exit(2);
        }
        String url = env("PGVECTOR_URL");
        String user = env("PGVECTOR_USER");
        String password = env("PGVECTOR_PASSWORD");
        if (url == null || user == null || password == null) {
            System.err.println("缺少环境变量 PGVECTOR_URL / PGVECTOR_USER / PGVECTOR_PASSWORD");
            System.exit(2);
        }

        List<String> statements = splitStatements(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
                System.out.println("OK: " + sql.replaceAll("\\s+", " ").trim().substring(0, Math.min(80,
                        sql.replaceAll("\\s+", " ").trim().length())));
            }
        }
        System.out.println("执行完成，共 " + statements.size() + " 条语句");
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v;
    }

    /** 去掉整行注释后按分号切分；空语句丢弃 */
    private static List<String> splitStatements(String raw) {
        StringBuilder buf = new StringBuilder();
        for (String line : raw.split("\\R")) {
            if (line.trim().startsWith("--")) {
                continue;
            }
            buf.append(line).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String part : buf.toString().split(";")) {
            String sql = part.trim();
            if (!sql.isEmpty()) {
                out.add(sql);
            }
        }
        return out;
    }
}
