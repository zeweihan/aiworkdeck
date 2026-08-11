package com.checkba.repository;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全实体的 @Index / @UniqueConstraint 物理列名对账（H2 侧，MODE=PostgreSQL）。
 *
 * 为什么需要这条：{@code @Table} 的 indexes/uniqueConstraints 里写的是**物理列名**，
 * 不走 PhysicalNamingStrategy 的驼峰转换。本仓没配自定义命名策略，Spring Boot 默认的
 * CamelCaseToUnderscoresNamingStrategy 会把字段 projectId 落成列 project_id；此处若写
 * 驼峰 "projectId"，Hibernate 找不到同名物理列，只会在启动日志里留一行 warn 就跳过，
 * 建表照常成功、索引却没建出来——线上表现是「悄悄变慢」，没有任何报错。
 *
 * 断言口径刻意不看索引名，只看列组合：H2 把 UNIQUE 约束的背书索引改名成
 * {@code IDX_XXX_INDEX_B}，按名字对账会假红。
 *
 * 用反射扫全包而不是硬编码清单：新加实体写错驼峰时，这条会自动红，不需要有人记得回来补。
 *
 * H2 内存库配方照抄 WorkSessionRepositoryTest:19-28，只改库名——@TestPropertySource
 * 参与 ApplicationContext 缓存键，换个库名就不会与其他 @DataJpaTest 互相污染。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:entity-index-naming-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class EntityIndexColumnNamingTest {

    @Autowired
    private DataSource dataSource;

    /** 一条 @Index 或 @UniqueConstraint 声明。 */
    private record DeclaredIndex(String entity, String table, String name, List<String> columns, boolean unique) {
        String describe() {
            return entity + " 的 " + (name.isBlank() ? "(未命名)" : name) + " " + columns;
        }
    }

    /** INFORMATION_SCHEMA 里读到的一条真实索引。 */
    private record ActualIndex(String name, List<String> columns, boolean unique) {
    }

    // ---------- 声明侧：反射扫全部 @Entity ----------

    private static List<DeclaredIndex> declaredIndexes() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<DeclaredIndex> declared = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.checkba")) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("扫到实体但加载不了：" + candidate.getBeanClassName(), e);
            }
            Table table = type.getAnnotation(Table.class);
            if (table == null || (table.indexes().length == 0 && table.uniqueConstraints().length == 0)) {
                continue;
            }
            assertFalse(table.name().isBlank(),
                    type.getSimpleName() + " 声明了索引/唯一约束，却没在 @Table 里写 name，无法对账物理表名");

            for (Index index : table.indexes()) {
                declared.add(new DeclaredIndex(type.getSimpleName(), table.name(), index.name(),
                        splitColumnList(index.columnList()), index.unique()));
            }
            for (UniqueConstraint constraint : table.uniqueConstraints()) {
                declared.add(new DeclaredIndex(type.getSimpleName(), table.name(), constraint.name(),
                        List.of(constraint.columnNames()), true));
            }
        }
        assertFalse(declared.isEmpty(), "一个声明索引的实体都没扫到，扫描包名或过滤器写错了");
        return declared;
    }

    /** columnList 允许写成 "a, b DESC"，物理列名只取排序方向前的那一段。 */
    private static List<String> splitColumnList(String columnList) {
        List<String> columns = new ArrayList<>();
        for (String raw : columnList.split(",")) {
            String column = raw.trim();
            if (column.isEmpty()) {
                continue;
            }
            int space = column.indexOf(' ');
            columns.add(space > 0 ? column.substring(0, space) : column);
        }
        return columns;
    }

    // ---------- 实际侧：读 INFORMATION_SCHEMA ----------

    /** 表名 -> 列名集合，全部大写（H2 对未加引号的标识符一律折成大写，PostgreSQL 模式下也一样）。 */
    private Map<String, Set<String>> physicalColumns() throws Exception {
        Map<String, Set<String>> byTable = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_SCHEMA = 'PUBLIC'")) {
            while (rs.next()) {
                byTable.computeIfAbsent(upper(rs.getString(1)), k -> new LinkedHashSet<>())
                        .add(upper(rs.getString(2)));
            }
        }
        return byTable;
    }

    /** 表名 -> 索引列表，列按 ORDINAL_POSITION 排好序。 */
    private Map<String, List<ActualIndex>> actualIndexes() throws Exception {
        Map<String, Map<String, List<String>>> columnsByTableAndIndex = new LinkedHashMap<>();
        Map<String, Map<String, Boolean>> uniqueByTableAndIndex = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT ic.TABLE_NAME, ic.INDEX_NAME, ic.COLUMN_NAME, i.INDEX_TYPE_NAME "
                             + "FROM INFORMATION_SCHEMA.INDEX_COLUMNS ic "
                             + "JOIN INFORMATION_SCHEMA.INDEXES i "
                             + "  ON i.INDEX_SCHEMA = ic.INDEX_SCHEMA "
                             + " AND i.TABLE_NAME = ic.TABLE_NAME "
                             + " AND i.INDEX_NAME = ic.INDEX_NAME "
                             + "WHERE ic.TABLE_SCHEMA = 'PUBLIC' "
                             + "ORDER BY ic.TABLE_NAME, ic.INDEX_NAME, ic.ORDINAL_POSITION")) {
            while (rs.next()) {
                String table = upper(rs.getString(1));
                String index = upper(rs.getString(2));
                columnsByTableAndIndex
                        .computeIfAbsent(table, k -> new LinkedHashMap<>())
                        .computeIfAbsent(index, k -> new ArrayList<>())
                        .add(upper(rs.getString(3)));
                uniqueByTableAndIndex
                        .computeIfAbsent(table, k -> new LinkedHashMap<>())
                        .put(index, upper(rs.getString(4)).contains("UNIQUE"));
            }
        }

        Map<String, List<ActualIndex>> byTable = new LinkedHashMap<>();
        columnsByTableAndIndex.forEach((table, perIndex) -> {
            List<ActualIndex> indexes = new ArrayList<>();
            perIndex.forEach((index, columns) ->
                    indexes.add(new ActualIndex(index, columns, uniqueByTableAndIndex.get(table).get(index))));
            byTable.put(table, indexes);
        });
        return byTable;
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static List<String> upper(List<String> values) {
        return values.stream().map(EntityIndexColumnNamingTest::upper).toList();
    }

    // ---------- 断言 ----------

    @Test
    void 索引里声明的列名必须是真实存在的物理列() throws Exception {
        Map<String, Set<String>> columns = physicalColumns();

        List<String> problems = new ArrayList<>();
        for (DeclaredIndex declared : declaredIndexes()) {
            Set<String> actual = columns.get(upper(declared.table()));
            if (actual == null) {
                problems.add(declared.describe() + "：表 " + declared.table() + " 没建出来");
                continue;
            }
            for (String column : declared.columns()) {
                if (!actual.contains(upper(column))) {
                    problems.add(declared.describe() + "：列 \"" + column + "\" 在表 " + declared.table()
                            + " 里不存在（该表实际列 " + actual + "）"
                            + "——@Table 的 indexes/uniqueConstraints 要写 snake_case 物理列名，不是 Java 驼峰字段名");
                }
            }
        }
        assertTrue(problems.isEmpty(), "以下索引声明引用了不存在的列：\n  " + String.join("\n  ", problems));
    }

    @Test
    void 声明的索引与唯一约束在H2上真的建出来了() throws Exception {
        Map<String, List<ActualIndex>> indexes = actualIndexes();

        List<String> problems = new ArrayList<>();
        for (DeclaredIndex declared : declaredIndexes()) {
            List<ActualIndex> actual = indexes.getOrDefault(upper(declared.table()), List.of());
            List<String> wanted = upper(declared.columns());

            // 按列组合对账而不是按名字：H2 会把 UNIQUE 约束的背书索引改名成 XXX_INDEX_B。
            ActualIndex hit = actual.stream()
                    .filter(index -> index.columns().equals(wanted))
                    .findFirst()
                    .orElse(null);

            if (hit == null) {
                problems.add(declared.describe() + "：表 " + declared.table() + " 上没有覆盖 " + wanted
                        + " 的索引（实际索引 " + actual.stream().map(ActualIndex::columns).toList() + "）");
            } else if (declared.unique() && !hit.unique()) {
                problems.add(declared.describe() + "：声明了 unique，实际索引 " + hit.name() + " 不是唯一索引");
            }
        }
        assertTrue(problems.isEmpty(), "以下索引没有在 H2 上建出来：\n  " + String.join("\n  ", problems));
    }
}
